/*
 * All content copyright (c) 2014 Terracotta, Inc., except as may otherwise
 * be noted in a separate copyright notice. All rights reserved.
 */
package com.tc.objectserver.handler;

import com.tc.async.api.AbstractEventHandler;
import com.tc.async.api.EventHandler;
import com.tc.async.api.EventHandlerException;
import com.tc.async.api.Sink;
import com.tc.l2.msg.ReplicationMessage;
import com.tc.l2.msg.ReplicationMessageAck;
import com.tc.net.groups.GroupException;
import com.tc.net.groups.GroupManager;
import com.tc.objectserver.api.EntityManager;
import com.tc.objectserver.entity.ActiveToPassiveReplication;
import com.tc.objectserver.persistence.EntityPersistor;
import com.tc.objectserver.persistence.TransactionOrderPersistor;

/**
 *
 */
public class ReplicatedTransactionHandler {
  

  private final EntityManager entityManager;
  private final EntityPersistor entityPersistor;
  private final GroupManager groupManager;
  private final ActiveToPassiveReplication replication;
  private final TransactionOrderPersistor orderedTransactions;
  private final Sink<Runnable> finalExecution;

  public ReplicatedTransactionHandler(ActiveToPassiveReplication replication, TransactionOrderPersistor transactionOrderPersistor, 
      EntityManager manager, EntityPersistor entityPersistor, GroupManager groupManager, Sink<Runnable> execution) {
    this.replication = replication;
    this.entityManager = manager;
    this.entityPersistor = entityPersistor;
    this.groupManager = groupManager;
    this.orderedTransactions = transactionOrderPersistor;
    this.finalExecution = execution;
  }

  private final EventHandler<ReplicationMessage> eventHorizon = new AbstractEventHandler<ReplicationMessage>() {
    @Override
    public void handleEvent(ReplicationMessage message) throws EventHandlerException {
      processMessage(message);
    }
  };
  
  public EventHandler<ReplicationMessage> getEventHandler() {
    return eventHorizon;
  }

  private void processMessage(ReplicationMessage rep) {
    try {
      if (rep.getType() == ReplicationMessage.REPLICATE) {
        if (!rep.getOldestTransactionOnClient().isNull()) {
          orderedTransactions.updateWithNewMessage(rep.getSource(), rep.getTransactionID(), rep.getOldestTransactionOnClient());
        } else {
          orderedTransactions.removeTrackingForClient(rep.getSource());
        }
        switch (rep.getAction()) {
          case ReplicationMessage.NOOP:
            break;
          case ReplicationMessage.CREATE_ENTITY:
            long consumerID = entityPersistor.getNextConsumerID();
            entityManager.createEntity(rep.getEntityDescriptor().getEntityID(), rep.getVersion(), consumerID);
            this.entityPersistor.entityCreated(rep.getEntityDescriptor().getEntityID(), rep.getVersion(), consumerID, rep.getExtendedData());
            break;
          case ReplicationMessage.DESTROY_ENTITY:
            entityManager.destroyEntity(rep.getEntityDescriptor().getEntityID());
            break;
          case ReplicationMessage.GET_ENTITY:
            break;
          case ReplicationMessage.INVOKE_ACTION:
            finalExecution.addMultiThreaded(null);
            break;
          case ReplicationMessage.RELEASE_ENTITY:
          case ReplicationMessage.PROMOTE_ENTITY_TO_ACTIVE:
          default:
            break;
          }
//  when is the right time to send the ack?
        groupManager.sendTo(rep.messageFrom(), new ReplicationMessageAck(rep.getMessageID()));
      } else {
        replication.acknowledge(rep);
      }
      return;
    } catch (GroupException ge) {

    }
    throw new RuntimeException();
  }
  
}
