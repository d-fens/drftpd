package net.sf.drftpd.slave;

import java.net.InetAddress;
import java.rmi.RemoteException;
import java.io.Serializable;

import net.sf.drftpd.master.*;
import net.sf.drftpd.master.SlaveManager;

/**
 * 
 */
public class RemoteSlave implements Serializable {
	protected long statusTime;
	protected SlaveStatus status;
	protected SlaveManagerImpl manager;
	protected String name="mog";
	public void setManager(SlaveManagerImpl manager) {
		this.manager = manager;
	}
	
	public SlaveManagerImpl getManager() {
		return manager;
	}
	
	public SlaveStatus getStatus() throws RemoteException {
		if(statusTime < System.currentTimeMillis()-10000) {
			status=getSlave().getSlaveStatus();
			statusTime = System.currentTimeMillis();
		}
		return status;
	}

	public RemoteSlave(Slave slave) {
		this.slave = slave;
	}

	protected Slave slave;
	public Slave getSlave() {
		return slave;
	}

	public String toString() {
		return slave.toString();
	}
	
	/**
	 * Returns the name.
	 */
	public String getName() {
		return name;
	}

}
