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

package org.voltcore.messaging;

import java.io.IOException;
import java.nio.ByteBuffer;


/**
 * Message from an initiator to an execution site, informing the
 * site that it may be requested to do work for a multi-partition
 * transaction, and to reserve a slot in its ordered work queue
 * for this transaction.
 *
 */
public abstract class TransactionInfoBaseMessage extends VoltMessage {

    protected long m_initiatorHSId;
    protected long m_coordinatorHSId;
    protected long m_txnId;
    // IV2: within a partition, the primary initiator and its replicas
    // use this for intra-partition ordering/lookup
    protected long m_spHandle;
    // IV2: allow PI to signal RI repair log truncation with a new task.
    private long m_truncationHandle;
    protected boolean m_isReadOnly;

    /** Empty constructor for de-serialization */
    protected TransactionInfoBaseMessage() {
        m_subject = Subject.DEFAULT.getId();
    }

    protected TransactionInfoBaseMessage(long initiatorHSId,
                                      long coordinatorHSId,
                                      long txnId,
                                      boolean isReadOnly) {
        m_initiatorHSId = initiatorHSId;
        m_coordinatorHSId = coordinatorHSId;
        m_txnId = txnId;
        m_isReadOnly = isReadOnly;
        m_subject = Subject.DEFAULT.getId();
        m_spHandle = Long.MIN_VALUE;
    }

    protected TransactionInfoBaseMessage(long initiatorHSId,
            long coordinatorHSId,
            TransactionInfoBaseMessage rhs)
    {
        m_initiatorHSId = initiatorHSId;
        m_coordinatorHSId = coordinatorHSId;
        m_txnId = rhs.m_txnId;
        m_isReadOnly = rhs.m_isReadOnly;
        m_subject = rhs.m_subject;
        m_spHandle = rhs.m_spHandle;
        m_truncationHandle = rhs.m_truncationHandle;
    }

    public long getInitiatorHSId() {
        return m_initiatorHSId;
    }

    public long getCoordinatorHSId() {
        return m_coordinatorHSId;
    }

    public void setTxnId(long txnId) {
        m_txnId = txnId;
    }

    public long getTxnId() {
        return m_txnId;
    }

    public void setSpHandle(long spHandle) {
        m_spHandle = spHandle;
    }

    public long getSpHandle() {
        return m_spHandle;
    }

    public void setTruncationHandle(long handle) {
        m_truncationHandle = handle;
    }

    public long getTruncationHandle() {
        return m_truncationHandle;
    }

    public boolean isReadOnly() {
        return m_isReadOnly;
    }

    public boolean isSinglePartition() {
        return false;
    }

    @Override
    public int getSerializedSize() {
        int msgsize = super.getSerializedSize();
        msgsize += 8   // m_initiatorHSId
            + 8        // m_coordinatorHSId
            + 8        // m_txnId
            + 8        // m_spHandle
            + 8        // m_truncationHandle
            + 1;       // m_isReadOnly
        return msgsize;
    }

    @Override
    public void flattenToBuffer(ByteBuffer buf) throws IOException {
        buf.putLong(m_initiatorHSId);
        buf.putLong(m_coordinatorHSId);
        buf.putLong(m_txnId);
        buf.putLong(m_spHandle);
        buf.putLong(m_truncationHandle);
        buf.put(m_isReadOnly ? (byte) 1 : (byte) 0);
    }

    @Override
    public void initFromBuffer(ByteBuffer buf) throws IOException {
        m_initiatorHSId = buf.getLong();
        m_coordinatorHSId = buf.getLong();
        m_txnId = buf.getLong();
        m_spHandle = buf.getLong();
        m_truncationHandle = buf.getLong();
        m_isReadOnly = buf.get() == 1;
    }
}
