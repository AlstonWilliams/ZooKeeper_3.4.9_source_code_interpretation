/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.quorum;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.apache.jute.Record;
import org.apache.zookeeper.server.ObserverBean;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.util.SerializeUtils;
import org.apache.zookeeper.txn.TxnHeader;

/**
 * Observers are peers that do not take part in the atomic broadcast protocol.
 * Instead, they are informed of successful proposals by the Leader. Observers
 * therefore naturally act as a relay point for publishing the proposal stream
 * and can relieve Followers of some of the connection load. Observers may
 * submit proposals, but do not vote in their acceptance. 
 *
 * See ZOOKEEPER-368 for a discussion of this feature. 
 */
public class Observer extends Learner{      

    Observer(QuorumPeer self,ObserverZooKeeperServer observerZooKeeperServer) {
        this.self = self;
        this.zk=observerZooKeeperServer;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Observer ").append(sock);        
        sb.append(" pendingRevalidationCount:")
            .append(pendingRevalidations.size());
        return sb.toString();
    }
    
    /**
     * the main method called by the observer to observe the leader
     *
     * @throws InterruptedException
     */
    void observeLeader() throws InterruptedException {
        zk.registerJMX(new ObserverBean(this, zk), self.jmxLocalPeerBean);

        try {
            /**
             *
             * Reading:
             *  Get the correct InetSocketAddress of leader
             *
             * */
            InetSocketAddress addr = findLeader();
            LOG.info("Observing " + addr);
            try {
                /**
                 *
                 * Reading:
                 *  Connect to server and allow specific timeout and specific deviation.
                 *
                 * */
                connectToLeader(addr);
                /**
                 *
                 * Reading:
                 *  Tell the server that I'm a observer and I wanna get your information.
                 *
                 * */
                long newLeaderZxid = registerWithLeader(Leader.OBSERVERINFO);

                /**
                 *
                 * Reading:
                 *  Synchronize data with leader to update and accept requests from clients, called when observer startup
                 *
                 * */
                syncWithLeader(newLeaderZxid);
                QuorumPacket qp = new QuorumPacket();
                /**
                 *
                 * Reading:
                 *  Get the data from leader and keep updating.
                 *
                 * */
                while (this.isRunning()) {
                    readPacket(qp);
                    processPacket(qp);                   
                }
            } catch (Exception e) {
                LOG.warn("Exception when observing the leader", e);
                try {
                    sock.close();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
    
                // clear pending revalidations
                pendingRevalidations.clear();
            }
        } finally {
            zk.unregisterJMX(this);
        }
    }
    
    /**
     * Controls the response of an observer to the receipt of a quorumpacket
     * @param qp
     * @throws IOException
     */
    protected void processPacket(QuorumPacket qp) throws IOException{
        switch (qp.getType()) {
        case Leader.PING:
            ping(qp);
            break;
        case Leader.PROPOSAL:
            LOG.warn("Ignoring proposal");
            break;
        case Leader.COMMIT:
            LOG.warn("Ignoring commit");            
            break;            
        case Leader.UPTODATE:
            LOG.error("Received an UPTODATE message after Observer started");
            break;
        case Leader.REVALIDATE:
            revalidate(qp);
            break;
        case Leader.SYNC:
            /**
             *
             * Reading:
             *  What function this block actually do?
             *
             * */
            ((ObserverZooKeeperServer)zk).sync();
            break;
        case Leader.INFORM:            
            TxnHeader hdr = new TxnHeader();
            Record txn = SerializeUtils.deserializeTxn(qp.getData(), hdr);
            Request request = new Request (null, hdr.getClientId(), 
                                           hdr.getCxid(),
                                           hdr.getType(), null, null);
            request.txn = txn;
            request.hdr = hdr;
            ObserverZooKeeperServer obs = (ObserverZooKeeperServer)zk;
            /**
             *
             * Reading:
             *  Build a Request and commit to ObserverZooKeeperServer.
             *  Why we need do this?
             *  Because I (a observer) receive {@link QuorumPacket} from Leader, and we need record them.
             *  Another reason is that, {@link org.apache.zookeeper.server.ZooKeeperServer} can process the request by chain.
             *
             * */
            obs.commitRequest(request);
            break;
        }
    }

    /**
     * Shutdown the Observer.
     */
    public void shutdown() {       
        LOG.info("shutdown called", new Exception("shutdown Observer"));
        super.shutdown();
    }
}

