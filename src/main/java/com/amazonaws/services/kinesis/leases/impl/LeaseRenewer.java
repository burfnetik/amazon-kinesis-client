/*
 * Copyright 2012-2013 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Amazon Software License (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 * http://aws.amazon.com/asl/
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.amazonaws.services.kinesis.leases.impl;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.amazonaws.services.cloudwatch.model.StandardUnit;
import com.amazonaws.services.kinesis.leases.exceptions.DependencyException;
import com.amazonaws.services.kinesis.leases.exceptions.InvalidStateException;
import com.amazonaws.services.kinesis.leases.exceptions.ProvisionedThroughputException;
import com.amazonaws.services.kinesis.leases.interfaces.ILeaseManager;
import com.amazonaws.services.kinesis.leases.interfaces.ILeaseRenewer;
import com.amazonaws.services.kinesis.metrics.impl.MetricsHelper;

/**
 * An implementation of ILeaseRenewer that uses DynamoDB via LeaseManager.
 */
public class LeaseRenewer<T extends Lease> implements ILeaseRenewer<T> {

    private static final Log LOG = LogFactory.getLog(LeaseRenewer.class);
    private static final int RENEWAL_RETRIES = 2;

    private final ILeaseManager<T> leaseManager;
    private final ConcurrentNavigableMap<String, T> ownedLeases = new ConcurrentSkipListMap<String, T>();
    private final String workerIdentifier;
    private final long leaseDurationNanos;

    /**
     * Constructor.
     * 
     * @param leaseManager LeaseManager to use
     * @param workerIdentifier identifier of this worker
     * @param leaseDurationMillis duration of a lease in milliseconds
     */
    public LeaseRenewer(ILeaseManager<T> leaseManager, String workerIdentifier, long leaseDurationMillis) {
        this.leaseManager = leaseManager;
        this.workerIdentifier = workerIdentifier;
        this.leaseDurationNanos = leaseDurationMillis * 1000000L;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void renewLeases() throws DependencyException, InvalidStateException {
        if (LOG.isDebugEnabled()) {
            // Due to the eventually consistent nature of ConcurrentNavigableMap iterators, this log entry may become
            // inaccurate during iteration.
            LOG.debug(String.format("Worker %s holding %d leases: %s",
                    workerIdentifier,
                    ownedLeases.size(),
                    ownedLeases));
        }

        /*
         * We iterate in descending order here so that the synchronized(lease) inside renewLease doesn't "lead" calls
         * to getCurrentlyHeldLeases. They'll still cross paths, but they won't interleave their executions.
         */
        int lostLeases = 0;
        for (T lease : ownedLeases.descendingMap().values()) {
            if (!renewLease(lease)) {
                lostLeases++;
            }
        }

        MetricsHelper.getMetricsScope().addData("LostLeases", lostLeases, StandardUnit.Count);
        MetricsHelper.getMetricsScope().addData("CurrentLeases", ownedLeases.size(), StandardUnit.Count);
    }

    private boolean renewLease(T lease) throws DependencyException, InvalidStateException {
        String leaseKey = lease.getLeaseKey();

        boolean success = false;
        boolean renewedLease = false;
        long startTime = System.currentTimeMillis();
        try {
            for (int i = 1; i <= RENEWAL_RETRIES; i++) {
                try {
                    synchronized (lease) {
                        renewedLease = leaseManager.renewLease(lease);
                        if (renewedLease) {
                            lease.setLastCounterIncrementNanos(System.nanoTime());
                        }
                    }

                    if (renewedLease) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug(String.format("Worker %s successfully renewed lease with key %s",
                                    workerIdentifier,
                                    leaseKey));
                        }
                    } else {
                        LOG.info(String.format("Worker %s lost lease with key %s", workerIdentifier, leaseKey));
                        ownedLeases.remove(leaseKey);
                    }

                    success = true;
                    break;
                } catch (ProvisionedThroughputException e) {
                    LOG.info(String.format("Worker %s could not renew lease with key %s on try %d out of %d due to capacity",
                            workerIdentifier,
                            leaseKey,
                            i,
                            RENEWAL_RETRIES));
                }
            }
        } finally {
            MetricsHelper.addSuccessAndLatency("RenewLease", startTime, success);
        }

        return renewedLease;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, T> getCurrentlyHeldLeases() {
        Map<String, T> result = new HashMap<String, T>();
        long now = System.nanoTime();

        for (String leaseKey : ownedLeases.keySet()) {
            T copy = getCopyOfHeldLease(leaseKey, now);
            if (copy != null) {
                result.put(copy.getLeaseKey(), copy);
            }
        }

        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T getCurrentlyHeldLease(String leaseKey) {
        return getCopyOfHeldLease(leaseKey, System.nanoTime());
    }

    /**
     * Internal method to return a lease with a specific lease key only if we currently hold it.
     * 
     * @param leaseKey key of lease to return
     * @param now current timestamp for old-ness checking
     * @return non-authoritative copy of the held lease, or null if we don't currently hold it
     */
    private T getCopyOfHeldLease(String leaseKey, long now) {
        T authoritativeLease = ownedLeases.get(leaseKey);
        if (authoritativeLease == null) {
            return null;
        } else {
            T copy = null;
            synchronized (authoritativeLease) {
                copy = authoritativeLease.copy();
            }

            if (copy.isExpired(leaseDurationNanos, now)) {
                LOG.info(String.format("getCurrentlyHeldLease not returning lease with key %s because it is expired",
                        copy.getLeaseKey()));
                return null;
            } else {
                return copy;
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean updateLease(T lease, UUID concurrencyToken)
        throws DependencyException, InvalidStateException, ProvisionedThroughputException {
        verifyNotNull(lease, "lease cannot be null");
        verifyNotNull(lease.getLeaseKey(), "leaseKey cannot be null");
        verifyNotNull(concurrencyToken, "concurrencyToken cannot be null");

        String leaseKey = lease.getLeaseKey();
        T authoritativeLease = ownedLeases.get(leaseKey);

        if (authoritativeLease == null) {
            LOG.info(String.format("Worker %s could not update lease with key %s because it does not hold it",
                    workerIdentifier,
                    leaseKey));
            return false;
        }

        /*
         * If the passed-in concurrency token doesn't match the concurrency token of the authoritative lease, it means
         * the lease was lost and regained between when the caller acquired his concurrency token and when the caller
         * called update.
         */
        if (!authoritativeLease.getConcurrencyToken().equals(concurrencyToken)) {
            LOG.info(String.format("Worker %s refusing to update lease with key %s because"
                    + " concurrency tokens don't match", workerIdentifier, leaseKey));
            return false;
        }

        long startTime = System.currentTimeMillis();
        boolean success = false;
        try {
            synchronized (authoritativeLease) {
                authoritativeLease.update(lease);
                boolean updatedLease = leaseManager.updateLease(authoritativeLease);
                if (updatedLease) {
                    // Updates increment the counter
                    authoritativeLease.setLastCounterIncrementNanos(System.nanoTime());
                } else {
                    /*
                     * If updateLease returns false, it means someone took the lease from us. Remove the lease
                     * from our set of owned leases pro-actively rather than waiting for a run of renewLeases().
                     */
                    LOG.info(String.format("Worker %s lost lease with key %s - discovered during update",
                            workerIdentifier,
                            leaseKey));

                    /*
                     * Remove only if the value currently in the map is the same as the authoritative lease. We're
                     * guarding against a pause after the concurrency token check above. It plays out like so:
                     * 
                     * 1) Concurrency token check passes
                     * 2) Pause. Lose lease, re-acquire lease. This requires at least one lease counter update.
                     * 3) Unpause. leaseManager.updateLease fails conditional write due to counter updates, returns
                     * false.
                     * 4) ownedLeases.remove(key, value) doesn't do anything because authoritativeLease does not
                     * .equals() the re-acquired version in the map on the basis of lease counter. This is what we want.
                     * If we just used ownedLease.remove(key), we would have pro-actively removed a lease incorrectly.
                     * 
                     * Note that there is a subtlety here - Lease.equals() deliberately does not check the concurrency
                     * token, but it does check the lease counter, so this scheme works.
                     */
                    ownedLeases.remove(leaseKey, authoritativeLease);
                }

                success = true;
                return updatedLease;
            }
        } finally {
            MetricsHelper.addSuccessAndLatency("UpdateLease", startTime, success);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addLeasesToRenew(Collection<T> newLeases) {
        verifyNotNull(newLeases, "newLeases cannot be null");

        for (T lease : newLeases) {
            if (lease.getLastCounterIncrementNanos() == null) {
                LOG.info(String.format("addLeasesToRenew ignoring lease with key %s because it does not have lastRenewalNanos set",
                        lease.getLeaseKey()));
                continue;
            }

            T authoritativeLease = lease.copy();

            /*
             * Assign a concurrency token when we add this to the set of currently owned leases. This ensures that
             * every time we acquire a lease, it gets a new concurrency token.
             */
            authoritativeLease.setConcurrencyToken(UUID.randomUUID());
            ownedLeases.put(authoritativeLease.getLeaseKey(), authoritativeLease);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clearCurrentlyHeldLeases() {
        ownedLeases.clear();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize() throws DependencyException, InvalidStateException, ProvisionedThroughputException {
        Collection<T> leases = leaseManager.listLeases();
        List<T> myLeases = new LinkedList<T>();

        for (T lease : leases) {
            if (workerIdentifier.equals(lease.getLeaseOwner())) {
                LOG.info(String.format(" Worker %s found lease %s", workerIdentifier, lease));
                if (renewLease(lease)) {
                    myLeases.add(lease);
                }
            } else {
                LOG.debug(String.format("Worker %s ignoring lease %s ", workerIdentifier, lease));
            }
        }

        addLeasesToRenew(myLeases);
    }
    
    private void verifyNotNull(Object object, String message) {
        if (object == null) {
            throw new IllegalArgumentException(message);
        }
    }

}
