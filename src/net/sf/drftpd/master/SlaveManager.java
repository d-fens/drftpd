/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * DrFTPD is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with DrFTPD; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package net.sf.drftpd.master;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;

import net.sf.drftpd.FatalException;
import net.sf.drftpd.NoAvailableSlaveException;
import net.sf.drftpd.ObjectNotFoundException;
import net.sf.drftpd.SlaveUnavailableException;
import net.sf.drftpd.master.config.FtpConfig;
import net.sf.drftpd.remotefile.MLSTSerialize;
import net.sf.drftpd.slave.SlaveStatus;
import net.sf.drftpd.util.SafeFileWriter;

import org.apache.log4j.Logger;

import org.drftpd.GlobalContext;

import org.drftpd.slave.async.AsyncCommandArgument;

import org.drftpd.slaveselection.SlaveSelectionManagerInterface;

import org.drftpd.usermanager.UserFileException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import java.net.ServerSocket;
import java.net.Socket;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Properties;
import java.util.Set;

/**
 * @author mog
 * @version $Id: SlaveManager.java,v 1.23 2004/11/08 05:25:12 zubov Exp $
 */
public class SlaveManager implements Runnable {
    private static final Logger logger = Logger.getLogger(SlaveManager.class
            .getName());

    private static final String slavePath = "slaves/";

    private static final File slavePathFile = new File(slavePath);

    protected GlobalContext _gctx;

    protected List _rslaves;

    private int _port;

    protected ServerSocket _serverSocket;

    public SlaveManager(Properties p, GlobalContext gctx)
            throws SlaveFileException {
        _gctx = gctx;
        _rslaves = new ArrayList();
        _port = Integer.parseInt(FtpConfig.getProperty(p, "master.bindport"));
        loadSlaves();
    }

    /**
     * For JUnit tests
     */
    public SlaveManager() {
        _rslaves = new ArrayList();
    }

    private void loadSlaves() throws SlaveFileException {
        _rslaves = new ArrayList();

        if (!slavePathFile.exists() && !slavePathFile.mkdirs()) {
            throw new SlaveFileException(new IOException(
                    "Error creating folders: " + slavePathFile));
        }

        String[] slavepaths = slavePathFile.list();

        for (int i = 0; i < slavepaths.length; i++) {
            String slavepath = slavepaths[i];

            if (!slavepath.endsWith(".xml")) {
                continue;
            }

            String slavename = slavepath.substring(0, slavepath.length()
                    - ".xml".length());

            try {
                getSlaveByNameUnchecked(slavename);
            } catch (ObjectNotFoundException e) {
                throw new SlaveFileException(e);
            }

            // throws IOException
        }

        Collections.sort(_rslaves);
    }

    public void newSlave(String slavename) {
        addSlave(new RemoteSlave(slavename, getGlobalContext()));
    }

    public void addSlave(RemoteSlave rslave) {
        _rslaves.add(rslave);
        Collections.sort(_rslaves);
    }

    private RemoteSlave getSlaveByNameUnchecked(String slavename)
            throws ObjectNotFoundException {
        if (slavename == null) {
            throw new NullPointerException();
        }

        RemoteSlave rslave = null;
        XStream inp = new XStream(new DomDriver());

        try {
            FileReader in = new FileReader(getSlaveFile(slavename));

            rslave = (RemoteSlave) inp.fromXML(in);
            rslave.init(slavename, getGlobalContext());

            //throws RuntimeException
            _rslaves.add(rslave);

            return rslave;
        } catch (FileNotFoundException e) {
            throw new ObjectNotFoundException(e);
        } catch (Exception e) {
            throw new FatalException(e);
        }
    }

    protected File getSlaveFile(String slavename) {
        return new File(slavePath + slavename + ".xml");
    }

    protected void addShutdownHook() {
        //add shutdown hook last
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                logger.info("Running shutdown hook");
                saveFilelist();

                try {
                    getGlobalContext().getConnectionManager()
                            .getGlobalContext().getUserManager().saveAll();
                } catch (UserFileException e) {
                    logger.warn("", e);
                }
            }
        });
    }

    public void delSlave(String slaveName) {
        RemoteSlave rslave = null;

        try {
            rslave = getRemoteSlave(slaveName);
            getSlaveFile(rslave.getName()).delete();
            rslave.setOffline("Slave has been deleted");
            _rslaves.remove(rslave);
            getGlobalContext().getRoot().unmergeDir(rslave);
        } catch (ObjectNotFoundException e) {
            throw new IllegalArgumentException("Slave not found");
        }
    }

    public HashSet findSlavesBySpace(int numOfSlaves, Set exemptSlaves,
            boolean ascending) {
        Collection slaveList = getGlobalContext().getSlaveManager().getSlaves();
        HashMap map = new HashMap();

        for (Iterator iter = slaveList.iterator(); iter.hasNext();) {
            RemoteSlave rslave = (RemoteSlave) iter.next();

            if (exemptSlaves.contains(rslave)) {
                continue;
            }

            Long size;

            try {
                size = new Long(rslave.getStatusAvailable()
                        .getDiskSpaceAvailable());
            } catch (SlaveUnavailableException e) {
                continue;
            }

            map.put(size, rslave);
        }

        ArrayList sorted = new ArrayList(map.keySet());

        if (ascending) {
            Collections.sort(sorted);
        } else {
            Collections.sort(sorted, Collections.reverseOrder());
        }

        HashSet returnMe = new HashSet();

        for (ListIterator iter = sorted.listIterator(); iter.hasNext();) {
            if (iter.nextIndex() == numOfSlaves) {
                break;
            }

            Long key = (Long) iter.next();
            RemoteSlave rslave = (RemoteSlave) map.get(key);
            returnMe.add(rslave);
        }

        return returnMe;
    }

    public RemoteSlave findSmallestFreeSlave() {
        Collection slaveList = getGlobalContext().getConnectionManager()
                .getGlobalContext().getSlaveManager().getSlaves();
        long smallSize = Integer.MAX_VALUE;
        RemoteSlave smallSlave = null;

        for (Iterator iter = slaveList.iterator(); iter.hasNext();) {
            RemoteSlave rslave = (RemoteSlave) iter.next();
            long size = Integer.MAX_VALUE;

            try {
                size = rslave.getStatusAvailable().getDiskSpaceAvailable();
            } catch (SlaveUnavailableException e) {
                continue;
            }

            if (size < smallSize) {
                smallSize = size;
                smallSlave = rslave;
            }
        }

        return smallSlave;
    }

    /**
     * Not cached at all since RemoteSlave objects cache their SlaveStatus
     */
    public SlaveStatus getAllStatus() {
        SlaveStatus allStatus = new SlaveStatus();

        for (Iterator iter = getSlaves().iterator(); iter.hasNext();) {
            RemoteSlave rslave = (RemoteSlave) iter.next();

            try {
                allStatus = allStatus.append(rslave.getStatusAvailable());
            } catch (SlaveUnavailableException e) {
                //slave is offline, continue
            }
        }

        return allStatus;
    }

    public HashMap getAllStatusArray() {
        //SlaveStatus[] ret = new SlaveStatus[getSlaves().size()];
        HashMap ret = new HashMap(getSlaves().size());

        for (Iterator iter = getSlaves().iterator(); iter.hasNext();) {
            RemoteSlave rslave = (RemoteSlave) iter.next();

            try {
                ret.put(rslave.getName(), rslave.getStatus());
            } catch (SlaveUnavailableException e) {
                ret.put(rslave.getName(), (Object) null);
            }
        }

        return ret;
    }

    //	private Random rand = new Random();
    //	public RemoteSlave getASlave() {
    //		ArrayList retSlaves = new ArrayList();
    //		for (Iterator iter = this.rslaves.iterator(); iter.hasNext();) {
    //			RemoteSlave rslave = (RemoteSlave) iter.next();
    //			if (!rslave.isAvailable())
    //				continue;
    //			retSlaves.add(rslave);
    //		}
    //
    //		int num = rand.nextInt(retSlaves.size());
    //		logger.fine(
    //			"Slave "
    //				+ num
    //				+ " selected out of "
    //				+ retSlaves.size()
    //				+ " available slaves");
    //		return (RemoteSlave) retSlaves.get(num);
    //	}
    public Collection getAvailableSlaves() throws NoAvailableSlaveException {
        ArrayList availableSlaves = new ArrayList();

        for (Iterator iter = getSlaves().iterator(); iter.hasNext();) {
            RemoteSlave rslave = (RemoteSlave) iter.next();

            if (!rslave.isAvailable()) {
                continue;
            }

            availableSlaves.add(rslave);
        }

        if (availableSlaves.isEmpty()) {
            throw new NoAvailableSlaveException("No slaves online");
        }

        return availableSlaves;
    }

    public GlobalContext getGlobalContext() {
        if (_gctx == null) {
            throw new NullPointerException();
        }

        return _gctx;
    }

    public RemoteSlave getRemoteSlave(String s) throws ObjectNotFoundException {
        for (Iterator iter = getSlaves().iterator(); iter.hasNext();) {
            RemoteSlave rslave = (RemoteSlave) iter.next();

            if (rslave.getName().equals(s)) {
                return rslave;
            }
        }

        return getSlaveByNameUnchecked(s);
    }

    public List getSlaves() {
        if (_rslaves == null) {
            throw new NullPointerException();
        }

        return Collections.unmodifiableList(_rslaves);
    }

    public SlaveSelectionManagerInterface getSlaveSelectionManager() {
        return getGlobalContext().getSlaveSelectionManager();
    }

    /**
     * Returns true if one or more slaves are online, false otherwise.
     * 
     * @return true if one or more slaves are online, false otherwise.
     */
    public boolean hasAvailableSlaves() {
        for (Iterator iter = _rslaves.iterator(); iter.hasNext();) {
            RemoteSlave rslave = (RemoteSlave) iter.next();

            if (rslave.isAvailable()) {
                return true;
            }
        }

        return false;
    }

    public void reload() throws FileNotFoundException, IOException {
    }

    public void saveFilelist() {
        try {
            SafeFileWriter out = new SafeFileWriter("files.mlst");

            try {
                MLSTSerialize.serialize(getGlobalContext()
                        .getConnectionManager().getGlobalContext().getRoot(),
                        out);
            } finally {
                out.close();
            }
        } catch (IOException e) {
            logger.warn("Error saving files.mlst", e);
        }
    }

    /** ping's all slaves, returns number of slaves removed */
    public int verifySlaves() {
        int removed = 0;

        synchronized (_rslaves) {
            for (Iterator i = _rslaves.iterator(); i.hasNext();) {
                RemoteSlave slave = (RemoteSlave) i.next();

                if (!slave.isAvailablePing()) {
                    removed++;
                }
            }
        }

        return removed;
    }

    public void run() {
        try {
            _serverSocket = new ServerSocket(_port);
            logger.info("Listening for slaves on port " + _port);
        } catch (Exception e) {
            throw new FatalException(e);
        }

        Socket socket = null;

        while (true) {
            RemoteSlave rslave = null;
            ObjectInputStream in = null;
            ObjectOutputStream out = null;

            try {
                socket = _serverSocket.accept();
                socket.setSoTimeout(0);
                logger.debug("Slave connected from "
                        + socket.getRemoteSocketAddress());

                in = new ObjectInputStream(socket.getInputStream());
                out = new ObjectOutputStream(socket.getOutputStream());
                String slavename = RemoteSlave
                .getSlaveNameFromObjectInput(in);
                try {
                rslave = getRemoteSlave(slavename);
                } catch (ObjectNotFoundException e) {
                    out.writeObject(new AsyncCommandArgument("", "error", slavename + " does not exist, use \"site addslave\""));
                    logger.info("Slave " + slavename + " does not exist, use \"site addslave\"");
                    return;
                }

                if (rslave.isOnlinePing()) {
                    out.writeObject(new AsyncCommandArgument("", "error",
                            "Already online"));
                    out.flush();
                    socket.close();
                    throw new IOException("Already online");
                }
            } catch (Exception e) {
                try {
                    socket.close();
                } catch (IOException e1) {
                }

                logger.error("", e);

                continue;
            }

            try {
                if (!rslave.checkConnect(socket)) {
                    out.writeObject(new AsyncCommandArgument("", "error",
                            socket.getInetAddress() + " is not a valid mask for "
                                    + rslave.getName()));
                    logger.error(socket.getInetAddress()
                            + " is not a valid ip for " + rslave.getName());
                    socket.close();
                    continue;
                }

                rslave.connect(socket, in, out);
            } catch (Exception e) {
                rslave.setOffline(e);
                logger.error(e);
            }
        }
    }
}