/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.jsmpp.session;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jsmpp.DefaultPDUReader;
import org.jsmpp.DefaultPDUSender;
import org.jsmpp.InvalidCommandLengthException;
import org.jsmpp.InvalidResponseException;
import org.jsmpp.PDUException;
import org.jsmpp.PDUReader;
import org.jsmpp.PDUSender;
import org.jsmpp.PDUStringException;
import org.jsmpp.SMPPConstant;
import org.jsmpp.SynchronizedPDUSender;
import org.jsmpp.bean.BindResp;
import org.jsmpp.bean.BindType;
import org.jsmpp.bean.Command;
import org.jsmpp.bean.DataSm;
import org.jsmpp.bean.DeliverSm;
import org.jsmpp.bean.InterfaceVersion;
import org.jsmpp.bean.NumberingPlanIndicator;
import org.jsmpp.bean.OptionalParameter;
import org.jsmpp.bean.OptionalParameter.Sc_interface_version;
import org.jsmpp.bean.Outbind;
import org.jsmpp.bean.TypeOfNumber;
import org.jsmpp.extra.NegativeResponseException;
import org.jsmpp.extra.PendingResponse;
import org.jsmpp.extra.ProcessRequestException;
import org.jsmpp.extra.QueueMaxException;
import org.jsmpp.extra.ResponseTimeoutException;
import org.jsmpp.extra.SessionState;
import org.jsmpp.session.connection.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * @author pmoerenhout
 */
public class SMPPOutboundServerSession extends AbstractSession implements OutboundServerSession {
  private static final Logger logger = LoggerFactory.getLogger(SMPPOutboundServerSession.class);

  private final Connection conn;
  private final DataInputStream in;
  private final OutputStream out;

  private final PDUReader pduReader;
  private final OutboundServerResponseHandler responseHandler = new OutboundServerResponseHandlerImpl();
  private BoundSessionStateListener sessionStateListener = new BoundSessionStateListener();
  private OutboundSMPPServerSessionContext sessionContext = new OutboundSMPPServerSessionContext(this, sessionStateListener);
  private PDUReaderWorker pduReaderWorker;
  private GenericMessageReceiverListener messageReceiverListener;
  private OutboundServerMessageReceiverListener outboundServerMessageReceiverListener;
  private OutbindRequestReceiver outbindRequestReceiver = new OutbindRequestReceiver();

  public SMPPOutboundServerSession(Connection conn,
                                   SessionStateListener sessionStateListener,
                                   GenericMessageReceiverListener messageReceiverListener,
                                   OutboundServerMessageReceiverListener outboundServerMessageReceiverListener,
                                   int pduProcessorDegree) {
    this(conn, sessionStateListener, messageReceiverListener,
        outboundServerMessageReceiverListener, pduProcessorDegree,
        new SynchronizedPDUSender(new DefaultPDUSender()),
        new DefaultPDUReader());
  }

  public SMPPOutboundServerSession(Connection conn,
                                   SessionStateListener sessionStateListener,
                                   GenericMessageReceiverListener messageReceiverListener,
                                   OutboundServerMessageReceiverListener outboundServerMessageReceiverListener,
                                   int pduProcessorDegree, PDUSender pduSender, PDUReader pduReader) {
    super(pduSender);
    this.conn = conn;
    this.messageReceiverListener = messageReceiverListener;
    this.outboundServerMessageReceiverListener = outboundServerMessageReceiverListener;
    this.pduReader = pduReader;
    this.in = new DataInputStream(conn.getInputStream());
    this.out = conn.getOutputStream();
    enquireLinkSender = new EnquireLinkSender();
    // addSessionStateListener(new OutboundSMPPServerSession.BoundStateListener());
    addSessionStateListener(sessionStateListener);
    setPduProcessorDegree(pduProcessorDegree);
    sessionContext.open();
  }

  public InetAddress getInetAddress() {
    return connection().getInetAddress();
  }

  public int getPort() {
    return connection().getPort();
  }

  /**
   * Sending bind.
   *
   * @param bindType         is the bind type.
   * @param systemId         is the system id.
   * @param password         is the password.
   * @param systemType       is the system type.
   * @param interfaceVersion is the interface version.
   * @param addrTon          is the address TON.
   * @param addrNpi          is the address NPI.
   * @param addressRange     is the address range.
   * @param timeout          is the max time waiting for bind response.
   * @return SMSC system id.
   * @throws PDUException              if we enter invalid bind parameter(s).
   * @throws ResponseTimeoutException  if there is no valid response after defined millisecond.
   * @throws InvalidResponseException  if there is invalid response found.
   * @throws NegativeResponseException if we receive negative response.
   * @throws IOException               if there is an IO error occur.
   */
  private String sendBind(BindType bindType, String systemId,
                          String password, String systemType,
                          InterfaceVersion interfaceVersion, TypeOfNumber addrTon,
                          NumberingPlanIndicator addrNpi, String addressRange, long timeout)
      throws PDUException, ResponseTimeoutException,
      InvalidResponseException, NegativeResponseException, IOException {

    BindCommandTask task = new BindCommandTask(pduSender(), bindType,
        systemId, password, systemType, interfaceVersion, addrTon,
        addrNpi, addressRange);

    BindResp resp = (BindResp) executeSendCommand(task, timeout);
    OptionalParameter.Sc_interface_version scVersion = resp.getOptionalParameter(Sc_interface_version.class);
    if (scVersion != null) {
      logger.debug("Other side reports SMPP interface version {}", scVersion);
    }

    logger.info("Bind response systemId '{}'", resp.getSystemId());
    return resp.getSystemId();
  }

  /**
   * Wait for outbind request.
   *
   * @param timeout is the timeout.
   * @return the {@link OutbindRequest}.
   * @throws IllegalStateException if this invocation of this method has been made or invoke when state is not OPEN.
   * @throws TimeoutException      if the timeout has been reach and {@link SMPPOutboundServerSession} are no more valid because the connection will be close
   *                               automatically.
   */
  public OutbindRequest waitForOutbind(long timeout)
      throws IllegalStateException, TimeoutException {
    SessionState currentSessionState = getSessionState();
    if (currentSessionState.equals(SessionState.OPEN)) {
      pduReaderWorker = new PDUReaderWorker(getQueueCapacity());
      pduReaderWorker.start();
      try {
        return outbindRequestReceiver.waitForRequest(timeout);
      } catch (IllegalStateException e) {
        throw new IllegalStateException(
            "Invocation of waitForOutbind() has been made", e);
      } catch (TimeoutException e) {
        close();
        throw e;
      }
    } else {
      throw new IllegalStateException(
          "waitForOutbind() should be invoked on OPEN state, actual state is "
              + currentSessionState);
    }
  }

  @Override
  protected Connection connection() {
    return conn;
  }

  @Override
  protected AbstractSessionContext sessionContext() {
    return sessionContext;
  }

  @Override
  protected GenericMessageReceiverListener messageReceiverListener() {
    return messageReceiverListener;
  }

  @Override
  protected void finalize() throws Throwable {
    close();
    super.finalize();
  }

  @Override
  public OutboundServerMessageReceiverListener getOutboundServerMessageReceiverListener() {
    return outboundServerMessageReceiverListener;
  }

  /**
   * Sets a message receiver listener for this smpp session.
   *
   * @param outboundServerMessageReceiverListener is the new listener
   */
  @Override
  public void setOutboundServerMessageReceiverListener(
      OutboundServerMessageReceiverListener outboundServerMessageReceiverListener) {
    this.outboundServerMessageReceiverListener = outboundServerMessageReceiverListener;
  }

  private void fireAcceptDeliverSm(DeliverSm deliverSm) throws ProcessRequestException {
    if (outboundServerMessageReceiverListener != null) {
      outboundServerMessageReceiverListener.onAcceptDeliverSm(deliverSm, this);
    } else {
      logger.warn("Receive deliver_sm but OutboundServerMessageReceiverListener is null. Short message = {}",
          new String(deliverSm.getShortMessage()));
      throw new ProcessRequestException("No message receiver listener registered", SMPPConstant.STAT_ESME_RX_T_APPN);
    }
  }

  /**
   * Bind immediately.
   *
   * @param bindParam is the bind parameters.
   * @param timeout   is the timeout.
   * @return the SMSC system id.
   * @throws IOException if there is an IO error found.
   */
  public String bind(BindParameter bindParam, long timeout)
      throws IOException {
    try {
      String smscSystemId = sendBind(bindParam.getBindType(), bindParam.getSystemId(), bindParam.getPassword(), bindParam.getSystemType(),
          bindParam.getInterfaceVersion(), bindParam.getAddrTon(), bindParam.getAddrNpi(), bindParam.getAddressRange(), timeout);
      sessionContext.bound(bindParam.getBindType());

      enquireLinkSender = new EnquireLinkSender();
      enquireLinkSender.start();

      return smscSystemId;
    } catch (PDUException e) {
      logger.error("Failed sending bind command", e);
      throw new IOException("Failed sending bind since some string parameter area invalid: " + e.getMessage(), e);
    } catch (NegativeResponseException e) {
      String message = "Receive negative bind response";
      logger.error(message, e);
      close();
      throw new IOException(message + ": " + e.getMessage(), e);
    } catch (InvalidResponseException e) {
      String message = "Receive invalid response of bind";
      logger.error(message, e);
      close();
      throw new IOException(message + ": " + e.getMessage(), e);
    } catch (ResponseTimeoutException e) {
      String message = "Wait for bind response timed out";
      logger.error(message, e);
      close();
      throw new IOException(message + ": " + e.getMessage(), e);
    } catch (IOException e) {
      logger.error("IO error occurred", e);
      close();
      throw e;
    }
  }

  private class OutboundServerResponseHandlerImpl implements OutboundServerResponseHandler {

    @Override
    public void processOutbind(Outbind outbind) throws ProcessRequestException {
      SMPPOutboundServerSession.this.outbindRequestReceiver.notifyAcceptOutbind(outbind);
      sessionContext.outbind();
    }

    @Override
    public void processDeliverSm(DeliverSm deliverSm) throws ProcessRequestException {
      try {
        fireAcceptDeliverSm(deliverSm);
      } catch (ProcessRequestException e) {
        throw e;
      } catch (Exception e) {
        String msg = "Invalid runtime exception thrown when processing deliver_sm";
        logger.error(msg, e);
        throw new ProcessRequestException(msg, SMPPConstant.STAT_ESME_RX_T_APPN);
      }
    }

    /* not used in outbound session */
    @Override
    public DataSmResult processDataSm(DataSm dataSm)
        throws ProcessRequestException {
      try {
        return fireAcceptDataSm(dataSm);
      } catch (Exception e) {
        String msg = "Invalid runtime exception thrown when processing DataSm";
        logger.error(msg, e);
        throw new ProcessRequestException(msg, SMPPConstant.STAT_ESME_RSYSERR);
      }
    }

    /* not used in outbound session */
    @Override
    public void sendDataSmResp(DataSmResult dataSmResult, int sequenceNumber)
        throws IOException {
      try {
        pduSender().sendDataSmResp(out, sequenceNumber,
            dataSmResult.getMessageId(),
            dataSmResult.getOptionalParameters());
      } catch (PDUStringException e) {
        /*
         * There should be no PDUStringException thrown since creation
         * of MessageId should be save.
         */
        logger.error("Failed sending data_sm_resp", e);
      }
    }

    @Override
    public PendingResponse<Command> removeSentItem(int sequenceNumber) {
      return removePendingResponse(sequenceNumber);
    }

    @Override
    public void notifyUnbonded() {
      sessionContext.unbound();
    }

    @Override
    public void sendDeliverSmResp(int commandStatus, int sequenceNumber, String messageId) throws IOException {
      pduSender().sendDeliverSmResp(out, commandStatus, sequenceNumber, messageId);
      logger.debug("deliver_sm_resp with sequence_number {} has been sent", sequenceNumber);
    }

    @Override
    public void sendEnquireLinkResp(int sequenceNumber) throws IOException {
      logger.debug("Sending enquire_link_resp");
      pduSender().sendEnquireLinkResp(out, sequenceNumber);
    }

    @Override
    public void sendGenerickNack(int commandStatus, int sequenceNumber) throws IOException {
      pduSender().sendGenericNack(out, commandStatus, sequenceNumber);
    }

    @Override
    public void sendNegativeResponse(int originalCommandId, int commandStatus, int sequenceNumber) throws IOException {
      pduSender().sendHeader(out, originalCommandId | SMPPConstant.MASK_CID_RESP, commandStatus, sequenceNumber);
    }

    @Override
    public void sendUnbindResp(int sequenceNumber) throws IOException {
      pduSender().sendUnbindResp(out, SMPPConstant.STAT_ESME_ROK, sequenceNumber);
    }
  }

  /**
   * Worker to read the PDU.
   *
   * @author uudashr
   */
  private class PDUReaderWorker extends Thread {
    // start with serial execution of pdu processing, when the session is bound the pool will be enlarge up to the PduProcessorDegree
    private ThreadPoolExecutor pduExecutor;
    private Runnable onIOExceptionTask = new Runnable() {
      @Override
      public void run() {
        close();
      }
    };

    PDUReaderWorker(final int queueCapacity) {
      super("PDUReaderWorker-" + getSessionId());
      pduExecutor = new ThreadPoolExecutor(1, 1,
          0L, TimeUnit.MILLISECONDS,
          new LinkedBlockingQueue<Runnable>(queueCapacity), new RejectedExecutionHandler() {
        @Override
        public void rejectedExecution(final Runnable runnable, final ThreadPoolExecutor executor) {
          logger.info("Receiving queue is full, please increasing queue capacity, and/or let other side obey the window size");
          Command pduHeader = ((PDUProcessServerTask) runnable).getPduHeader();
          if ((pduHeader.getCommandId() & SMPPConstant.MASK_CID_RESP) == SMPPConstant.MASK_CID_RESP) {
            try {
              boolean success = executor.getQueue().offer(runnable, 60000, TimeUnit.MILLISECONDS);
              if (!success) {
                logger.warn("Offer to queue failed for {}", pduHeader);
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          } else {
            throw new QueueMaxException("Queue capacity " + queueCapacity + " exceeded");
          }
        }
      });
    }

    @Override
    public void run() {
      logger.info("Starting PDUReaderWorker");
      while (isReadPdu()) {
        readPDU();
      }
      logger.info("Close PDUReaderWorker");
      close();
      pduExecutor.shutdown();
      try {
        pduExecutor.awaitTermination(getTransactionTimer(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        logger.warn("Interrupted while waiting for PDU executor pool to finish");
        Thread.currentThread().interrupt();
      }
      logger.debug("{} stopped", getName());
    }

    private void readPDU() {
      Command pduHeader = null;
      try {
        pduHeader = pduReader.readPDUHeader(in);
        byte[] pdu = pduReader.readPDU(in, pduHeader);
        /*
         * When the processing PDU is need user interaction via event,
         * the code on event might take non-short time, so we need to
         * process it concurrently.
         */
        PDUProcessOutboundServerTask task = new PDUProcessOutboundServerTask(pduHeader, pdu,
            sessionContext, responseHandler,
            sessionContext, onIOExceptionTask);
        pduExecutor.execute(task);
      } catch (QueueMaxException e) {
        logger.info("Notify other side to throttle: {} ({} threads active)", e.getMessage(), pduExecutor.getActiveCount());
        try {
          responseHandler.sendNegativeResponse(pduHeader.getCommandId(), SMPPConstant.STAT_ESME_RTHROTTLED, pduHeader.getSequenceNumber());
        } catch (IOException ioe) {
          logger.warn("Failed sending negative response: {}", ioe.getMessage());
          close();
        }
      } catch (InvalidCommandLengthException e) {
        logger.warn("Received invalid command length: {}", e.getMessage());
        try {
          pduSender().sendGenericNack(out, SMPPConstant.STAT_ESME_RINVCMDLEN, 0);
        } catch (IOException ee) {
          logger.warn("Failed sending generic nack", ee);
        }
        unbindAndClose();
      } catch (SocketTimeoutException e) {
        notifyNoActivity();
      } catch (EOFException e) {
        if (sessionContext.getSessionState() == SessionState.UNBOUND) {
          logger.debug("Unbound session {} socket closed", getSessionId());
        } else {
          logger.warn("Session {} socket closed unexpected", getSessionId());
        }
        close();
      } catch (IOException e) {
        logger.info("Reading PDU session {} in state {}: {}", getSessionId(), getSessionState(), e.getMessage());
        close();
      } catch (RuntimeException e) {
        logger.warn("Runtime error while reading", e);
        close();
      }
    }

    /**
     * Notify for no activity.
     */
    private void notifyNoActivity() {
      logger.debug("No activity notified, sending enquireLink");
      if (sessionContext().getSessionState().isBound()) {
        enquireLinkSender.enquireLink();
      }
    }

  }

  private class BoundSessionStateListener implements SessionStateListener {
    @Override
    public void onStateChange(SessionState newState, SessionState oldState,
                              Session source) {
      /*
       * We need to set SO_TIMEOUT to sessionTimer so when timeout occur,
       * a SocketTimeoutException will be raised. When Exception raised we
       * can send an enquireLinkCommand.
       */
      if (newState.isBound()) {
        try {
          conn.setSoTimeout(getEnquireLinkTimer());
        } catch (IOException e) {
          logger.error("Failed setting so_timeout for session timer", e);
        }

        logger.info("Changing processor degree to {}", getPduProcessorDegree());
        pduReaderWorker.pduExecutor.setMaximumPoolSize(getPduProcessorDegree());
        pduReaderWorker.pduExecutor.setCorePoolSize(getPduProcessorDegree());
      }
    }
  }
}
