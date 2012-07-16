/* This file is part of VoltDB.
 * Copyright (C) 2008-2012 VoltDB Inc.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.iv2;

import java.util.ArrayList;
import java.util.Comparator;

import java.util.concurrent.Future;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeSet;

import org.apache.zookeeper_voltpatches.KeeperException;
import org.apache.zookeeper_voltpatches.ZooKeeper;

import org.json_voltpatches.JSONException;
import org.json_voltpatches.JSONObject;

import org.voltcore.logging.VoltLogger;
import org.voltcore.messaging.VoltMessage;

import org.voltcore.utils.CoreUtils;
import org.voltcore.zk.MapCache;
import org.voltcore.zk.MapCacheWriter;

import org.voltdb.messaging.Iv2RepairLogRequestMessage;
import org.voltdb.messaging.Iv2RepairLogResponseMessage;
import org.voltdb.VoltDB;

public class SpPromoteAlgo implements RepairAlgo
{
    VoltLogger tmLog = new VoltLogger("TM");
    private final String m_whoami;

    private final InitiatorMailbox m_mailbox;
    private final int m_partitionId;
    private final long m_requestId = System.nanoTime();
    private final ZooKeeper m_zk;
    private final String m_mapCacheNode;
    private final List<Long> m_survivors;

    // Each Term can process at most one promotion; if promotion fails, make
    // a new Term and try again (if that's your big plan...)
    private final InaugurationFuture m_promotionResult = new InaugurationFuture();

    long getRequestId()
    {
        return m_requestId;
    }

    // scoreboard for responding replica repair log responses (hsid -> response count)
    static class ReplicaRepairStruct
    {
        int m_receivedResponses = 0;
        int m_expectedResponses = -1; // (a log msg cares about this init. value)
        long m_maxSpHandleSeen = Long.MIN_VALUE;

        // update counters and return the number of outstanding messages.
        int update(Iv2RepairLogResponseMessage response)
        {
            m_receivedResponses++;
            m_expectedResponses = response.getOfTotal();
            m_maxSpHandleSeen = Math.max(m_maxSpHandleSeen, response.getSpHandle());
            return logsComplete();
        }

        // return 0 if all expected logs have been received.
        int logsComplete()
        {
            // expected responses is really a count of remote
            // messages. if there aren't any, the sequence will be
            // 1 (the count of responses) while expected will be 0
            // (the length of the remote log)
            if (m_expectedResponses == 0) {
               return 0;
            }
            return m_expectedResponses - m_receivedResponses;
        }

        // return true if this replica needs the message for spHandle.
        boolean needs(long spHandle)
        {
            return m_maxSpHandleSeen < spHandle;
        }
    }

    // replicas being processed and repaired.
    Map<Long, ReplicaRepairStruct> m_replicaRepairStructs =
        new HashMap<Long, ReplicaRepairStruct>();

    // Determine equal repair responses by the SpHandle of the response.
    Comparator<Iv2RepairLogResponseMessage> m_unionComparator =
        new Comparator<Iv2RepairLogResponseMessage>()
    {
        @Override
        public int compare(Iv2RepairLogResponseMessage o1, Iv2RepairLogResponseMessage o2)
        {
            return (int)(o1.getSpHandle() - o2.getSpHandle());
        }
    };

    // Union of repair responses.
    TreeSet<Iv2RepairLogResponseMessage> m_repairLogUnion =
        new TreeSet<Iv2RepairLogResponseMessage>(m_unionComparator);

    /**
     * Setup a new RepairAlgo but don't take any action to take responsibility.
     */
    public SpPromoteAlgo(List<Long> survivors, ZooKeeper zk,
            int partitionId, InitiatorMailbox mailbox,
            String zkMapCacheNode, String whoami)
    {
        m_zk = zk;
        m_partitionId = partitionId;
        m_mailbox = mailbox;
        m_survivors = survivors;

        m_whoami = whoami;
        m_mapCacheNode = zkMapCacheNode;
    }

    @Override
    public Future<Boolean> start()
    {
        try {
            prepareForFaultRecovery();
        } catch (Exception e) {
            tmLog.error(m_whoami + "failed leader promotion:", e);
            m_promotionResult.setException(e);
            m_promotionResult.done();
        }
        return m_promotionResult;
    }

    @Override
    public boolean cancel()
    {
        return m_promotionResult.cancel(false);
    }

    /** Start fixing survivors: setup scoreboard and request repair logs. */
    void prepareForFaultRecovery()
    {
        for (Long hsid : m_survivors) {
            m_replicaRepairStructs.put(hsid, new ReplicaRepairStruct());
        }

        tmLog.info(m_whoami + "found (including self) " + m_survivors.size()
                + " surviving replicas to repair. "
                + " Survivors: " + CoreUtils.hsIdCollectionToString(m_survivors));
        VoltMessage logRequest = new Iv2RepairLogRequestMessage(m_requestId);
        m_mailbox.send(com.google.common.primitives.Longs.toArray(m_survivors), logRequest);
    }

    /** Process a new repair log response */
    @Override
    public void deliver(VoltMessage message)
    {
        if (message instanceof Iv2RepairLogResponseMessage) {
            Iv2RepairLogResponseMessage response = (Iv2RepairLogResponseMessage)message;
            if (response.getRequestId() != m_requestId) {
                tmLog.info(m_whoami + "rejecting stale repair response."
                        + " Current request id is: " + m_requestId
                        + " Received response for request id: " + response.getRequestId());
                return;
            }
            ReplicaRepairStruct rrs = m_replicaRepairStructs.get(response.m_sourceHSId);
            if (rrs.m_expectedResponses < 0) {
                tmLog.info(m_whoami + "collecting " + response.getOfTotal()
                        + " repair log entries from "
                        + CoreUtils.hsIdToString(response.m_sourceHSId));
            }
            m_repairLogUnion.add(response);
            if (rrs.update(response) == 0) {
                tmLog.info(m_whoami + "collected " + rrs.m_receivedResponses
                        + " responses for " + rrs.m_expectedResponses +
                        " repair log entries from " + CoreUtils.hsIdToString(response.m_sourceHSId));
                if (areRepairLogsComplete()) {
                    repairSurvivors();
                }
            }
        }
    }

    /** Have all survivors supplied a full repair log? */
    public boolean areRepairLogsComplete()
    {
        for (Entry<Long, ReplicaRepairStruct> entry : m_replicaRepairStructs.entrySet()) {
            if (entry.getValue().logsComplete() != 0) {
                return false;
            }
        }
        return true;
    }

    /** Send missed-messages to survivors. Exciting! */
    public void repairSurvivors()
    {
        // cancel() and repair() must be synchronized by the caller (the deliver lock,
        // currently). If cancelled and the last repair message arrives, don't send
        // out corrections!
        if (this.m_promotionResult.isCancelled()) {
            tmLog.debug(m_whoami + "Skipping repair message creation for cancelled Term.");
            return;
        }

        int queued = 0;
        tmLog.info(m_whoami + "received all repair logs and is repairing surviving replicas.");
        for (Iv2RepairLogResponseMessage li : m_repairLogUnion) {
            List<Long> needsRepair = new ArrayList<Long>(5);
            for (Entry<Long, ReplicaRepairStruct> entry : m_replicaRepairStructs.entrySet()) {
                if  (entry.getValue().needs(li.getSpHandle())) {
                    ++queued;
                    tmLog.debug(m_whoami + "repairing " + entry.getKey() + ". Max seen " +
                            entry.getValue().m_maxSpHandleSeen + ". Repairing with " +
                            li.getSpHandle());
                    needsRepair.add(entry.getKey());
                }
            }
            if (!needsRepair.isEmpty()) {
                m_mailbox.repairReplicasWith(needsRepair, li);
            }
        }
        tmLog.info(m_whoami + "finished queuing " + queued + " replica repair messages.");

        // Can't run ZK work on a Network thread. Hack up a new context here.
        // See ENG-3176
        Thread declareLeaderThread = new Thread() {
            @Override
            public void run() {
                declareReadyAsLeader();
            }
        };
        declareLeaderThread.start();
    }

    // with leadership election complete, update the master list
    // for non-initiator components that care.
    void declareReadyAsLeader()
    {
        try {
            MapCacheWriter iv2masters = new MapCache(m_zk, m_mapCacheNode);
            iv2masters.put(Integer.toString(m_partitionId),
                    new JSONObject("{hsid:" + m_mailbox.getHSId() + "}"));
            m_promotionResult.done();
        } catch (KeeperException e) {
            VoltDB.crashLocalVoltDB("Bad news: failed to declare leader.", true, e);
        } catch (InterruptedException e) {
            VoltDB.crashLocalVoltDB("Bad news: failed to declare leader.", true, e);
        } catch (JSONException e) {
            VoltDB.crashLocalVoltDB("Bad news: failed to declare leader.", true, e);
        }
    }
}