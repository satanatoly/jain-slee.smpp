package org.restcomm.slee.resource.smpp;

import javax.slee.resource.ActivityHandle;

public class SmppTransactionHandle implements ActivityHandle {

	private final String smppSessionConfigurationName;
	private final SmppTransactionType smppTransactionType;
	private final int seqNumnber;
	private final long localSessionId;
	private transient SmppTransactionImpl activity;

	public SmppTransactionHandle(String smppSessionConfigurationName, int seqNumnber,
			SmppTransactionType smppTransactionType, long localSessionId) {
		this.smppSessionConfigurationName = smppSessionConfigurationName;
		this.seqNumnber = seqNumnber;
		this.smppTransactionType = smppTransactionType;
		this.localSessionId = localSessionId;
	}

	public SmppTransactionImpl getActivity() {
		return activity;
	}

	public void setActivity(SmppTransactionImpl activity) {
		this.activity = activity;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + seqNumnber;
		result = prime * result + (int)localSessionId;
		result = prime * result + smppTransactionType.hashCode();
		result = prime * result + smppSessionConfigurationName.hashCode();
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		SmppTransactionHandle other = (SmppTransactionHandle) obj;
		if (seqNumnber != other.seqNumnber)
			return false;
		if (localSessionId != other.localSessionId)
		    return false;
		if (smppTransactionType != other.smppTransactionType)
			return false;
		if (!smppSessionConfigurationName.equals(other.smppSessionConfigurationName))
			return false;
		return true;
	}

	@Override
	public String toString() {
		return "SmppTransactionHandle [smppSessionConfigurationName=" + smppSessionConfigurationName
				+ ", smppTransactionType=" + smppTransactionType + ", seqNumnber=" + seqNumnber 
				+ ", localSessionId = " + localSessionId + "]";
	}

}
