package org.restcomm.slee.resource.smpp;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.slee.facilities.Tracer;

import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppSessionCounters;
import com.cloudhopper.smpp.impl.DefaultSmppSession;
import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.type.RecoverablePduException;
import com.cloudhopper.smpp.type.SmppChannelException;
import com.cloudhopper.smpp.type.UnrecoverablePduException;

public class ResponseSender extends Thread {
    private volatile Boolean running = true;
    private LinkedBlockingQueue<SmppResponseTask> queue = new LinkedBlockingQueue<SmppResponseTask>();
    private long timeout;

    private SmppServerResourceAdaptor smppServerResourceAdaptor;
    private Tracer tracer;

    private AtomicLong lastOfferTimestamp = new AtomicLong();
    
    public ResponseSender(SmppServerResourceAdaptor smppServerResourceAdaptor, Tracer tracer, String name, long timeout) {
        super(name);
        this.timeout = timeout;
        this.smppServerResourceAdaptor = smppServerResourceAdaptor;
        this.tracer = tracer;
        this.tracer.warning(name + "created");
    }

    public void deactivate() {
        running = false;
        this.interrupt();
        while (!queue.isEmpty()) {
            SmppResponseTask task = queue.poll();
            if (task != null) {
                Exception ex = new InterruptedException(getName() + " was stopped");
                fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(),
                        task.getResponse(), ex, false);
            }
        }
        this.tracer.warning(getName() + "deactivated");
    }

    public void run() {
        while (running) {
            SmppResponseTask task = null;
            try {
                task = queue.poll(timeout, TimeUnit.MILLISECONDS);
                if (task != null) {
                    DefaultSmppSession defaultSmppSession = task.getEsme().getSmppSession();
                    try {
                        lastOfferTimestamp.set(System.currentTimeMillis());
                        defaultSmppSession.sendResponsePdu(task.getResponse());
                        fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(),
                                task.getResponse(), null, true);
                    } catch (RecoverablePduException e) {
                        fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(),
                                task.getResponse(), e, false);
                    } catch (UnrecoverablePduException e) {
                        fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(),
                                task.getResponse(), e, false);
                    } catch (SmppChannelException e) {
                        fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(),
                                task.getResponse(), e, false);
                    } catch (InterruptedException e) {
                        tracer.warning(EsmeSender.LOGGER_TAG, e);
                        fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(),
                                task.getResponse(), e, false);
                    } finally {
                        SmppSessionCounters smppSessionCounters = task.getEsme().getSmppSession().getCounters();
                        SmppTransactionImpl smppTransactionImpl = (SmppTransactionImpl) task.getRequest().getReferenceObject();
                        long responseTime = System.currentTimeMillis() - smppTransactionImpl.getStartTime();
                        countSendResponsePdu(smppSessionCounters, task.getResponse(), responseTime, responseTime);
                    }
                }
            } catch (Exception e) {
                tracer.severe("Exception when sending of sendResponsePdu: " + e.getMessage(), e);
                if (task != null) {
                    fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(),
                            task.getResponse(), e, false);
                }
            }
        }
    }

    public void offer(SmppResponseTask task) {
        logQueueSizeIfNecessary();
        logPreviousTaskLongRunIfNecessary();
        queue.offer(task);
    }

    private static final int[] SIZE_STEP_ARRAY  = {10,100,200,500,1000};
    private void logQueueSizeIfNecessary() {
        int queueSize = queue.size();
        for (int i = 0; i < SIZE_STEP_ARRAY.length; i++) {
            int step = SIZE_STEP_ARRAY[i];
            if(queueSize % step == 0) {
                tracer.warning(EsmeSender.LOGGER_TAG + " response queue size reached " + step);
                break;
            }
        }
    }
    
    private static final int[] SIZE_STEP_ARRAY_2  = {1, 10,100,200,500,1000};
    private void logPreviousTaskLongRunIfNecessary() {
        int queueSize = queue.size();
        if(queueSize > 0) {
            long diff = System.currentTimeMillis() - lastOfferTimestamp.get();
            if(diff > 1000) {
                for (int i = 0; i < SIZE_STEP_ARRAY_2.length; i++) {
                    int step = SIZE_STEP_ARRAY_2[i];
                    if(queueSize % step == 0) {
                        tracer.warning(EsmeSender.LOGGER_TAG + " previous response task takes too long to execute, diff:" + diff + "ms");
                        break;
                    }
                } 
            }
        }
    }

    private void fireSendPduStatusEvent(String systemId, SmppTransactionImpl smppServerTransaction, PduRequest request,
            PduResponse response, Throwable exception, boolean status) {

        SendPduStatus event = new SendPduStatus(exception, request, response, systemId, status);

        try {
            smppServerResourceAdaptor.fireEvent(systemId, smppServerTransaction.getActivityHandle(), event);
        } catch (Exception e) {
            tracer.severe(String.format(
                    "Received fireRecoverablePduException. Error while processing RecoverablePduException=%s", event), e);
        } finally {
            if (smppServerTransaction == null) {
                tracer.severe(String.format("SmppTransactionImpl Activity is null while trying to send PduResponse=%s",
                        response));
            } else {
                this.smppServerResourceAdaptor.endActivity(smppServerTransaction);
            }
        }
    }

    private void countSendResponsePdu(SmppSessionCounters counters, PduResponse pdu, long responseTime,
            long estimatedProcessingTime) {
        if (pdu.isResponse()) {
            switch (pdu.getCommandId()) {
                case SmppConstants.CMD_ID_SUBMIT_SM_RESP:
                    counters.getRxSubmitSM().incrementResponseAndGet();
                    counters.getRxSubmitSM().addRequestResponseTimeAndGet(responseTime);
                    counters.getRxSubmitSM().addRequestEstimatedProcessingTimeAndGet(estimatedProcessingTime);
                    counters.getRxSubmitSM().getResponseCommandStatusCounter().incrementAndGet(pdu.getCommandStatus());
                    break;
                case SmppConstants.CMD_ID_DELIVER_SM_RESP:
                    counters.getRxDeliverSM().incrementResponseAndGet();
                    counters.getRxDeliverSM().addRequestResponseTimeAndGet(responseTime);
                    counters.getRxDeliverSM().addRequestEstimatedProcessingTimeAndGet(estimatedProcessingTime);
                    counters.getRxDeliverSM().getResponseCommandStatusCounter().incrementAndGet(pdu.getCommandStatus());
                    break;
                case SmppConstants.CMD_ID_DATA_SM_RESP:
                    counters.getRxDataSM().incrementResponseAndGet();
                    counters.getRxDataSM().addRequestResponseTimeAndGet(responseTime);
                    counters.getRxDataSM().addRequestEstimatedProcessingTimeAndGet(estimatedProcessingTime);
                    counters.getRxDataSM().getResponseCommandStatusCounter().incrementAndGet(pdu.getCommandStatus());
                    break;
                case SmppConstants.CMD_ID_ENQUIRE_LINK_RESP:
                    counters.getRxEnquireLink().incrementResponseAndGet();
                    counters.getRxEnquireLink().addRequestResponseTimeAndGet(responseTime);
                    counters.getRxEnquireLink().addRequestEstimatedProcessingTimeAndGet(estimatedProcessingTime);
                    counters.getRxEnquireLink().getResponseCommandStatusCounter().incrementAndGet(pdu.getCommandStatus());
                    break;

            // TODO: adding here statistics for SUBMIT_MULTI ?
            }
        }
    }

    public long getPreviousIterationTime() {
        return lastOfferTimestamp.get();
    }
}
