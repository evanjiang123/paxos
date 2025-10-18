package comp512st.paxos;

import java.io.Serializable;

class PaxosMessage implements Serializable {
    int position;  
    PaxosMessage(int position) {
        this.position = position;
    }
}

// for deciding which proposer takes the lead
class Ballot implements Serializable, Comparable<Ballot> {
    int ballotId;
    String processId;

    Ballot(int ballotId, String processId) {
        this.ballotId = ballotId;
        this.processId = processId;
    }

    @Override
    public int compareTo(Ballot other) {
        // compare by ballotId first, then by processId
        if (this.ballotId != other.ballotId) {
            return Integer.compare(this.ballotId, other.ballotId);
        }
        return this.processId.compareTo(other.processId);
    }

    @Override
    public boolean equals(Object obj) {
        Ballot other = (Ballot) obj;
        return this.ballotId == other.ballotId && this.processId.equals(other.processId);
    }

    @Override
    public int hashCode() {
        return ballotId * 31 + processId.hashCode();
    }

    @Override
    public String toString() {
        return "(" + ballotId + "," + processId + ")";
    }
}

// sent by proposers, propose to become the leader
class ProposeMessage extends PaxosMessage {
    Ballot ballot;
    ProposeMessage(int position, Ballot ballot) {
        super(position);
        this.ballot = ballot;
    }

    @Override
    public String toString() {
        return "PROPOSE[pos=" + position + ", ballot=" + ballot + "]";
    }
}

// sent by acceptors, promise to not accept any values with ballotid lower than the current, etc
class PromiseMessage extends PaxosMessage {
    Ballot ballot;
    Ballot acceptedBallot; 
    Object acceptedValue;   

    PromiseMessage(int position, Ballot ballot, Ballot acceptedBallot, Object acceptedValue) {
        super(position);
        this.ballot = ballot;
        this.acceptedBallot = acceptedBallot;
        this.acceptedValue = acceptedValue;
    }

    @Override
    public String toString() {
        return "PROMISE[pos=" + position + ", ballot=" + ballot +
               ", prevBallot=" + acceptedBallot + ", prevValue=" + acceptedValue + "]";
    }
}

// sent by acceptors, refuses, it already has a ballot id higher than the current one
class RefuseMessage extends PaxosMessage {
    Ballot highestBallot;

    RefuseMessage(int position, Ballot highestBallot) {
        super(position);
        this.highestBallot = highestBallot;
    }

    @Override
    public String toString() {
        return "REFUSE[pos=" + position + ", highestBallot=" + highestBallot + "]";
    }
}

// sent by proposer, asks acceptor to accept the value
class AcceptMessage extends PaxosMessage {
    Ballot ballot;
    Object value; 

    AcceptMessage(int position, Ballot ballot, Object value) {
        super(position);
        this.ballot = ballot;
        this.value = value;
    }

    @Override
    public String toString() {
        return "ACCEPT[pos=" + position + ", ballot=" + ballot + ", value=" + value + "]";
    }
}

// sent by acceptor, acknowledgement for acceptance
class AckMessage extends PaxosMessage {
    Ballot ballot;

    AckMessage(int position, Ballot ballot) {
        super(position);
        this.ballot = ballot;
    }

    @Override
    public String toString() {
        return "ACK[pos=" + position + ", ballot=" + ballot + "]";
    }
}

// sent by acceptor, can't accept value
class RejectMessage extends PaxosMessage {
    Ballot highestBallot;

    RejectMessage(int position, Ballot highestBallot) {
        super(position);
        this.highestBallot = highestBallot;
    }

    @Override
    public String toString() {
        return "REJECT[pos=" + position + ", highestBallot=" + highestBallot + "]";
    }
}

// sent by proposer after majority accepts to inform everyone about the decided value
class ConfirmMessage extends PaxosMessage {
    Object value;

    ConfirmMessage(int position, Object value) {
        super(position);
        this.value = value;
    }

    @Override
    public String toString() {
        return "CONFIRM[pos=" + position + ", value=" + value + "]";
    }
}
