package comp512st.paxos;

// Access to the GCL layer
import comp512.gcl.*;
import comp512.utils.*;

// Any other imports that you may need.
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import java.net.UnknownHostException;


// ANY OTHER classes, etc., that you add must be private to this package and not visible to the application layer.
// extend / implement whatever interface, etc. as required.
// NO OTHER public members / methods allowed. broadcastTOMsg, acceptTOMsg, and shutdownPaxos must be the only visible methods to the application layer.

public class Paxos{

	GCL gcl;
	FailCheck failCheck;
	Logger logger;

	// group members 
	String myProcessId;
	String[] allProcesses;
	int majority;

	// ballot counter, and next position to propose, to deliver
	private int myBallotCounter = 0;
	private int nextProposalPosition = 1;  
	private int deliveryPosition = 1;     

	// tracks consensus state for each slot
	private Map<Integer, PositionState> positionStates = new ConcurrentHashMap<>();

	// queues for coordination between threads
	private BlockingQueue<Object> pendingMoves = new LinkedBlockingQueue<>();
	private Map<Integer, Object> deliveryQueue = new ConcurrentHashMap<>();

	// threading
	private Thread messageListenerThread;
	private Thread proposerThread;
	private volatile boolean running = true;

	// Synchronization for delivery
	private final Object deliveryLock = new Object();


	private class PositionState {
		int position;

		// acceptor state
		Ballot promisedBallot = null;      
		Ballot acceptedBallot = null;      
		Object acceptedValue = null;       

		// proposer state
		Ballot proposedBallot = null;
		Set<String> receivedPromises = new HashSet<>();
		Set<String> receivedAcks = new HashSet<>();      

		// promises with and accepted values
		Map<String, PromiseMessage> promiseMessages = new HashMap<>();

		// confirm state: final decision
		Object decidedValue = null;        
		boolean decided = false;           

		// Synchronization: latches to wait for majority
		CountDownLatch promiseLatch = null;
		CountDownLatch ackLatch = null;

		PositionState(int position) {
			this.position = position;
		}
	}

	private PositionState getOrCreatePositionState(int position) {
		return positionStates.computeIfAbsent(position, PositionState::new);
	}



	public Paxos(String myProcess, String[] allGroupProcesses, Logger logger, FailCheck failCheck) throws IOException, UnknownHostException
	{
		// Rember to call the failCheck.checkFailure(..) with appropriate arguments throughout your Paxos code to force fail points if necessary.
		this.failCheck = failCheck;
		this.logger = logger;
		this.myProcessId = myProcess;
		this.allProcesses = allGroupProcesses;
		this.majority = (allGroupProcesses.length / 2) + 1;

		
		this.gcl = new GCL(myProcess, allGroupProcesses, null, logger);
		logger.info("Paxos initialized: myProcess=" + myProcess +
		            ", totalProcesses=" + allGroupProcesses.length +
		            ", majority=" + majority);

		
		startMessageListener();
		startProposerThread();
	}


	private void startMessageListener() {
		messageListenerThread = new Thread(() -> {
			logger.info("Message listener thread started");

			while (running) {
				try {
					GCMessage gcMsg = gcl.readGCMessage();
					handleIncomingMessage(gcMsg);

				} catch (InterruptedException e) {
					if (!running) break;
					logger.warning("Message listener interrupted");
				}
			}
			logger.info("Message listener thread stopped");
		});
		messageListenerThread.start();
	}

	private void handleIncomingMessage(GCMessage gcMsg) {
		Object msg = gcMsg.val;

		if (msg instanceof ProposeMessage) {
			handlePropose((ProposeMessage) msg, gcMsg.senderProcess);
		} else if (msg instanceof PromiseMessage) {
			handlePromise((PromiseMessage) msg, gcMsg.senderProcess);
		} else if (msg instanceof RefuseMessage) {
			handleRefuse((RefuseMessage) msg, gcMsg.senderProcess);
		} else if (msg instanceof AcceptMessage) {
			handleAccept((AcceptMessage) msg, gcMsg.senderProcess);
		} else if (msg instanceof AckMessage) {
			handleAck((AckMessage) msg, gcMsg.senderProcess);
		} else if (msg instanceof RejectMessage) {
			handleReject((RejectMessage) msg, gcMsg.senderProcess);
		} else if (msg instanceof ConfirmMessage) {
			ConfirmMessage confirmMsg = (ConfirmMessage) msg;
			handleConfirm(confirmMsg.position, confirmMsg.value);
		} else {
			logger.warning("Unknown message type: " + msg.getClass());
		}
	}


	private void startProposerThread() {
		proposerThread = new Thread(() -> {
			logger.info("Proposer thread started");

			while (running) {
				try {
					
					Object value = pendingMoves.take();

					int position;
					synchronized (this) {position = nextProposalPosition++;}
					logger.info("Starting Paxos for position " + position + " with value: " + value);
					runPaxos(position, value);

				} catch (InterruptedException e) {
					if (!running) break; 
					logger.warning("Proposer thread interrupted");
				}
			}
			logger.info("Proposer thread stopped");
		});
		proposerThread.start();
	}
	

	private void runPaxos(int position, Object value) {

		
		PositionState state = getOrCreatePositionState(position);
		while (true) {
			// check if already slot already decided
			synchronized (state) {
				if (state.decided) {
					logger.info("Position " + position + " already decided with value: " + state.decidedValue);
					if (!state.decidedValue.equals(value)) {
						logger.info("try next position");
						pendingMoves.add(value);
					}
					return;
				}
			}

			// generalize ballot and initailize state for this propose
			Ballot ballot;
			synchronized (this) {
				ballot = new Ballot(myBallotCounter++, myProcessId);
			}
			logger.info("Position " + position + ": Starting proposal with ballot " + ballot);

			synchronized (state) {
				state.proposedBallot = ballot;
				state.receivedPromises.clear();
				state.receivedAcks.clear();
				state.promiseMessages.clear();
				state.promiseLatch = new CountDownLatch(majority);
				state.ackLatch = new CountDownLatch(majority);
			}

			// propose to everyone
			logger.info("Position " + position + ":  sending propose with ballot " + ballot);
			ProposeMessage propose = new ProposeMessage(position, ballot);
			for (String process : allProcesses) {
				gcl.sendMsg(propose, process);
			}
			failCheck.checkFailure(FailCheck.FailureType.AFTERSENDPROPOSE);

			// try to get majority promises
			try {
				boolean gotMajority = state.promiseLatch.await(5, TimeUnit.SECONDS);
				if (!gotMajority) {
					logger.warning("Position " + position + ": Timeout waiting for promises, retrying with higher ballot");
					continue;
				}
			} catch (InterruptedException e) {
				logger.warning("Position " + position + ": Interrupted waiting for promises");
				return;
			}


			synchronized (state) {
				if (state.receivedPromises.size() < majority) {
					logger.warning("Position " + position + ": Lost promises, retrying");
					continue;
				}
			}
			failCheck.checkFailure(FailCheck.FailureType.AFTERBECOMINGLEADER);

			// if previously accepted value, then propose that value, else propose our own value
			Object valueToPropose = value;
			synchronized (state) {
				Ballot highestAcceptedBallot = null;
				for (PromiseMessage promiseMsg : state.promiseMessages.values()) {
					if (promiseMsg.acceptedBallot != null) {
						if (highestAcceptedBallot == null || promiseMsg.acceptedBallot.compareTo(highestAcceptedBallot) > 0) {
							highestAcceptedBallot = promiseMsg.acceptedBallot;
							valueToPropose = promiseMsg.acceptedValue;
						}
					}
				}

				if (valueToPropose != value) {
					logger.info("Position " + position + ": Must propose previously accepted value");
				}
			}

			// sending accept messages to everyone
			logger.info("Position " + position + ": sending accept with ballot " + ballot + " and value " + valueToPropose);
			AcceptMessage accept = new AcceptMessage(position, ballot, valueToPropose);

			for (String process : allProcesses) {
				gcl.sendMsg(accept, process);
			}

			// try to get majority ack
			try {
				boolean gotMajority = state.ackLatch.await(5, TimeUnit.SECONDS);
				if (!gotMajority) {
					logger.warning("Position " + position + ": Timeout waiting for acks, retrying with higher ballot");
					continue;
				}
			} catch (InterruptedException e) {
				logger.warning("Position " + position + ": Interrupted waiting for acks");
				return;
			}

			synchronized (state) {
				if (state.receivedAcks.size() < majority) {
					logger.warning("Position " + position + ": Lost acks, retrying");
					continue;
				}
			}
			failCheck.checkFailure(FailCheck.FailureType.AFTERVALUEACCEPT);

			// Consensus reached, sending confirm to everyone
			logger.info("Position " + position + ": Consensus reached! Decided value: " + valueToPropose);
			synchronized (state) {
				state.decided = true;
				state.decidedValue = valueToPropose;
			}
			ConfirmMessage confirm = new ConfirmMessage(position, valueToPropose);
			for (String process : allProcesses) {
				gcl.sendMsg(confirm, process);
			}

			synchronized (deliveryLock) {
				deliveryQueue.put(position, valueToPropose);
				deliveryLock.notifyAll();
			}

			logger.info("Position " + position + ": Paxos complete for value " + valueToPropose);
			if (!valueToPropose.equals(value)) {
				logger.info("Our value " + value + " lost to " + valueToPropose + ", re-proposing in next position");
				pendingMoves.add(value);
			}

			return;
		}
	}

	private void handlePropose(ProposeMessage msg, String sender) {
		logger.info("Received propose from " + sender + ": " + msg);

		// Fail point 1: RECEIVEPROPOSE
		failCheck.checkFailure(FailCheck.FailureType.RECEIVEPROPOSE);

		PositionState state = getOrCreatePositionState(msg.position);
		synchronized (state) {
			if (state.promisedBallot == null || msg.ballot.compareTo(state.promisedBallot) > 0) {
				state.promisedBallot = msg.ballot;

				PromiseMessage promise = new PromiseMessage(msg.position, msg.ballot, state.acceptedBallot, state.acceptedValue);
				gcl.sendMsg(promise, sender);
				failCheck.checkFailure(FailCheck.FailureType.AFTERSENDVOTE);
				logger.info("Sent promise to " + sender + " for position " + msg.position + " ballot " + msg.ballot);

			} else {
				RefuseMessage refuse = new RefuseMessage(msg.position, state.promisedBallot);
				gcl.sendMsg(refuse, sender);
				failCheck.checkFailure(FailCheck.FailureType.AFTERSENDVOTE);
				logger.info("Sent refuse to " + sender + " for position " + msg.position +", already promised " + state.promisedBallot);
			}
		}
	}

	private void handlePromise(PromiseMessage msg, String sender) {
		logger.info("Received promise from " + sender + ": " + msg);

		PositionState state = positionStates.get(msg.position);
		if (state == null) return; 

		synchronized (state) {
			if (state.proposedBallot != null && msg.ballot.equals(state.proposedBallot)) {
				state.receivedPromises.add(sender);
				state.promiseMessages.put(sender, msg);
				logger.info("Position " + msg.position + ": Promise count = " + state.receivedPromises.size() + "/" + majority);

				if (state.promiseLatch != null) {
					state.promiseLatch.countDown();
				}
			}
		}
	}

	private void handleRefuse(RefuseMessage msg, String sender) {
		logger.info("Received refuse from " + sender + ": " + msg);

		synchronized (this) {
			if (msg.highestBallot.ballotId >= myBallotCounter) {
				myBallotCounter = msg.highestBallot.ballotId + 1;
				logger.info("Updated ballot counter to " + myBallotCounter +" after refuse from " + sender);
			}
		}
	}

	private void handleAccept(AcceptMessage msg, String sender) {
		logger.info("Received accept from " + sender + ": " + msg);

		PositionState state = getOrCreatePositionState(msg.position);
		synchronized (state) {
			if (state.promisedBallot == null || msg.ballot.compareTo(state.promisedBallot) >= 0) {
				state.promisedBallot = msg.ballot;
				state.acceptedBallot = msg.ballot;
				state.acceptedValue = msg.value;

				AckMessage ack = new AckMessage(msg.position, msg.ballot);
				gcl.sendMsg(ack, sender);
				failCheck.checkFailure(FailCheck.FailureType.AFTERSENDVOTE);
				logger.info("Sent ack to " + sender + " for position " + msg.position + " ballot " + msg.ballot);

			} else {
				RejectMessage reject = new RejectMessage(msg.position, state.promisedBallot);
				gcl.sendMsg(reject, sender);
				logger.info("Sent reject to " + sender + " for position " + msg.position + ", already promised " + state.promisedBallot);
			}
		}
	}

	private void handleAck(AckMessage msg, String sender) {
		logger.info("Received ack from " + sender + ": " + msg);

		PositionState state = positionStates.get(msg.position);
		if (state == null) return;

		synchronized (state) {
			if (state.proposedBallot != null && msg.ballot.equals(state.proposedBallot)) {
				state.receivedAcks.add(sender);
				logger.info("Position " + msg.position + ": ack count = " + state.receivedAcks.size() + "/" + majority);

				if (state.ackLatch != null) {
					state.ackLatch.countDown();
				}
			}
		}
	}

	private void handleReject(RejectMessage msg, String sender) {
		logger.info("Received reject from " + sender + ": " + msg);

		synchronized (this) {
			if (msg.highestBallot.ballotId >= myBallotCounter) {
				myBallotCounter = msg.highestBallot.ballotId + 1;
				logger.info("Updated ballot counter to " + myBallotCounter + " after reject from " + sender);
			}
		}
	}

	private void handleConfirm(int position, Object value) {
		logger.info("Received confirm for position " + position + " with value: " + value);

		PositionState state = getOrCreatePositionState(position);
		synchronized (state) {
			if (!state.decided) {
				state.decided = true;
				state.decidedValue = value;
				logger.info("Position " + position + " decided: " + value);
			}
		}

		synchronized (deliveryLock) {
			deliveryQueue.put(position, value);
			deliveryLock.notifyAll();
		}
		logger.info("Position " + position + " added to delivery queue");
	}

	


	public void broadcastTOMsg(Object val){

		logger.info("broadcastTOMsg called with value: " + val);
		try {
			pendingMoves.put(val);
			logger.info("Value added to pending queue: " + val);
		} catch (InterruptedException e) {
			logger.warning("Interrupted while adding to pending queue");
			Thread.currentThread().interrupt();
		}
	}

	
	public Object acceptTOMsg() throws InterruptedException
	{
		synchronized (deliveryLock) {
			while (!deliveryQueue.containsKey(deliveryPosition)) {
				logger.info("Waiting for position " + deliveryPosition + " to be decided");
				deliveryLock.wait();
			}

			
			Object value = deliveryQueue.get(deliveryPosition);
			logger.info("Delivering position " + deliveryPosition + " with value: " + value);
			deliveryQueue.remove(deliveryPosition);
			deliveryPosition++;

			return value;
		}
	}

	public void shutdownPaxos()
	{
		logger.info("Shutting down Paxos...");
		running = false;

		if (messageListenerThread != null) {
			messageListenerThread.interrupt();
		}
		if (proposerThread != null) {
			proposerThread.interrupt();
		}

		gcl.shutdownGCL();
		logger.info("Paxos shutdown complete");
	}
}

