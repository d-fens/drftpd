/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 *
 * DrFTPD is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * DrFTPD; if not, write to the Free Software Foundation, Inc., 59 Temple Place,
 * Suite 330, Boston, MA 02111-1307 USA
 */
package org.drftpd.commands;

import net.sf.drftpd.Bytes;
import net.sf.drftpd.DuplicateElementException;
import net.sf.drftpd.HostMask;
import net.sf.drftpd.master.BaseFtpConnection;
import net.sf.drftpd.master.FtpReply;
import net.sf.drftpd.master.FtpRequest;
import net.sf.drftpd.master.command.CommandManager;
import net.sf.drftpd.master.command.CommandManagerFactory;
import net.sf.drftpd.master.config.FtpConfig;
import net.sf.drftpd.master.config.Permission;
import net.sf.drftpd.util.ReplacerUtils;
import net.sf.drftpd.util.Time;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import org.drftpd.slave.RemoteTransfer;

import org.drftpd.usermanager.Key;
import org.drftpd.usermanager.NoSuchUserException;
import org.drftpd.usermanager.User;
import org.drftpd.usermanager.UserExistsException;
import org.drftpd.usermanager.UserFileException;

import org.tanesha.replacer.FormatterException;
import org.tanesha.replacer.ReplacerEnvironment;
import org.tanesha.replacer.ReplacerFormat;
import org.tanesha.replacer.SimplePrintf;

import java.text.ParseException;
import java.text.SimpleDateFormat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;


/**
 * @author mog
 * @author zubov
 * @version $Id: UserManagment.java,v 1.1 2004/11/05 13:27:21 mog Exp $
 */
public class UserManagment implements CommandHandler, CommandHandlerFactory {
    public static final Key TAGLINE = new Key(UserManagment.class, "tagline",
            String.class);
    private static final Logger logger = Logger.getLogger(UserManagment.class);
    public static final Key RATIO = new Key(UserManagment.class, "ratio",
            Float.class);

    private FtpReply doSITE_ADDIP(BaseFtpConnection conn) {
        FtpRequest request = conn.getRequest();

        if (!conn.getUserNull().isAdmin() &&
                !conn.getUserNull().isGroupAdmin()) {
            return FtpReply.RESPONSE_530_ACCESS_DENIED;
        }

        if (!request.hasArgument()) {
            return new FtpReply(501,
                conn.jprintf(UserManagment.class, "addip.usage"));
        }

        String[] args = request.getArgument().split(" ");

        if (args.length < 2) {
            return new FtpReply(501,
                conn.jprintf(UserManagment.class, "addip.specify"));
        }

        FtpReply response = new FtpReply(200);
        User myUser;

        try {
            myUser = conn.getGlobalContext().getUserManager().getUserByName(args[0]);

            if (conn.getUserNull().isGroupAdmin() &&
                    !conn.getUserNull().getGroupName().equals(myUser.getGroupName())) {
                return FtpReply.RESPONSE_530_ACCESS_DENIED;
            }

            ReplacerEnvironment env = new ReplacerEnvironment();
            env.add("targetuser", myUser.getUsername());

            for (int i = 1; i < args.length; i++) {
                String string = args[i];
                env.add("mask", string);

                try {
                    myUser.addIPMask(string);
                    response.addComment(conn.jprintf(UserManagment.class,
                            "addip.success", env));
                    logger.info("'" + conn.getUserNull().getUsername() +
                        "' added ip '" + string + "' to '" +
                        myUser.getUsername() + "'");
                } catch (DuplicateElementException e) {
                    response.addComment(conn.jprintf(UserManagment.class,
                            "addip.dupe", env));
                }
            }

            myUser.commit(); // throws UserFileException

            //userManager.save(user2);
        } catch (NoSuchUserException ex) {
            return new FtpReply(200, "No such user: " + args[0]);
        } catch (UserFileException ex) {
            response.addComment(ex.getMessage());

            return response;
        }

        return response;
    }

    /**
     * USAGE: site adduser <user><password>[ <ident@ip#1>... <ident@ip#5>] Adds
     * a user. You can have wild cards for users that have dynamic ips Examples:
     * *@192.168.1.* , frank@192.168.*.* , bob@192.*.*.* (*@192.168.1.1[5-9]
     * will allow only 192.168.1.15-19 to connect but no one else)
     *
     * If a user is added by a groupadmin, that user will have the GLOCK flag
     * enabled and will inherit the groupadmin's home directory.
     *
     * All default values for the user are read from file default.user in
     * /glftpd/ftp-data/users. Comments inside describe what is what. Gadmins
     * can be assigned their own default. <group>userfiles as templates to be
     * used when they add a user, if one is not found, default.user will be
     * used. default.groupname files will also be used for "site gadduser".
     *
     * ex. site ADDUSER Archimede mypassword
     *
     * This would add the user 'Archimede' with the password 'mypassword'.
     *
     * ex. site ADDUSER Archimede mypassword *@127.0.0.1
     *
     * This would do the same as above + add the ip '*@127.0.0.1' at the same
     * time.
     *
     * HOMEDIRS: After login, the user will automatically be transferred into
     * his/her homedir. As of 1.16.x this dir is now "kinda" chroot'ed and they
     * are now unable to "cd ..".
     *
     *
     *
     * USAGE: site gadduser <group><user><password>[ <ident@ip#1 ..
     * ident@ip#5>] Adds a user and changes his/her group to <group>. If
     * default.group exists, it will be used as a base instead of default.user.
     *
     * Only public groups can be used as <group>.
     */
    private FtpReply doSITE_ADDUSER(BaseFtpConnection conn) {
        FtpRequest request = conn.getRequest();
        boolean isGAdduser = request.getCommand().equals("SITE GADDUSER");

        if (!request.hasArgument()) {
            String key;

            if (isGAdduser) {
                key = "gadduser.usage";
            } else { //request.getCommand().equals("SITE ADDUSER");
                key = "adduser.usage";
            }

            return new FtpReply(501, conn.jprintf(UserManagment.class, key));
        }

        String newGroup = null;

        if (conn.getUserNull().isGroupAdmin()) {
            if (isGAdduser) {
                return FtpReply.RESPONSE_530_ACCESS_DENIED;
            }

            int users;

            try {
                users = conn.getGlobalContext().getUserManager()
                            .getAllUsersByGroup(conn.getUserNull().getGroupName())
                            .size();
                logger.debug("Group " + conn.getUserNull().getGroupName() +
                    " is " +
                    conn.getGlobalContext().getUserManager().getAllUsersByGroup(conn.getUserNull()
                                                                                    .getGroupName()));

                if (users >= conn.getUserNull().getGroupSlots()) {
                    return new FtpReply(200,
                        conn.jprintf(UserManagment.class, "adduser.noslots"));
                }
            } catch (UserFileException e1) {
                logger.warn("", e1);

                return new FtpReply(200, e1.getMessage());
            }

            newGroup = conn.getUserNull().getGroupName();
        } else if (!conn.getUserNull().isAdmin()) {
            return FtpReply.RESPONSE_530_ACCESS_DENIED;
        }

        StringTokenizer st = new StringTokenizer(request.getArgument());
        User newUser;
        FtpReply response = (FtpReply) FtpReply.RESPONSE_200_COMMAND_OK.clone();
        ReplacerEnvironment env = new ReplacerEnvironment();

        try {
            if (isGAdduser) {
                newGroup = st.nextToken();
            }

            String newUsername = st.nextToken();
            env.add("targetuser", newUsername);

            String pass = st.nextToken();

            //action, no more NoSuchElementException below here
            newUser = conn.getGlobalContext().getUserManager().create(newUsername);
            newUser.setPassword(pass);
            response.addComment(conn.jprintf(UserManagment.class,
                    "adduser.success", env));
            newUser.setComment("Added by " + conn.getUserNull().getUsername());

            if (newGroup != null) {
                newUser.setGroup(newGroup);
                logger.info("'" + conn.getUserNull().getUsername() +
                    "' added '" + newUser.getUsername() + "' with group " +
                    newUser.getGroupName() + "'");
                env.add("primgroup", newUser.getGroupName());
                response.addComment(conn.jprintf(UserManagment.class,
                        "adduser.primgroup", env));
            } else {
                logger.info("'" + conn.getUserNull().getUsername() +
                    "' added '" + newUser.getUsername() + "'");
            }
        } catch (NoSuchElementException ex) {
            return new FtpReply(501,
                conn.jprintf(UserManagment.class, "adduser.missingpass"));
        } catch (UserFileException ex) {
            return new FtpReply(200, ex.getMessage());
        }

        try {
            while (st.hasMoreTokens()) {
                String string = st.nextToken();
                env.add("mask", string);
                new HostMask(string); // validate hostmask

                try {
                    newUser.addIPMask(string);
                    response.addComment(conn.jprintf(UserManagment.class,
                            "addip.success", env));
                    logger.info("'" + conn.getUserNull().getUsername() +
                        "' added ip '" + string + "' to '" +
                        newUser.getUsername() + "'");
                } catch (DuplicateElementException e1) {
                    response.addComment(conn.jprintf(UserManagment.class,
                            "addip.dupe", env));
                }
            }

            newUser.commit();
        } catch (UserFileException ex) {
            logger.warn("", ex);

            return new FtpReply(200, ex.getMessage());
        }

        return response;
    }

    /**
     * USAGE: site change <user><field><value>- change a field for a user site
     * change =<group><field><value>- change a field for each member of group
     * <group>site change {<user1><user2>.. }<field><value>- change a field
     * for each user in the list site change *<field><value>- change a field
     * for everyone
     *
     * Type "site change user help" in glftpd for syntax.
     *
     * Fields available:
     *
     * Field Description
     * ------------------------------------------------------------- ratio
     * Upload/Download ratio. 0 = Unlimited (Leech) wkly_allotment The number of
     * kilobytes that this user will be given once a week (you need the reset
     * binary enabled in your crontab). Syntax: site change user wkly_allotment
     * "#,###" The first number is the section number (0=default section), the
     * second is the number of kilobytes to give. (user's credits are replaced,
     * not added to, with this value) Only one section at a time is supported,
     * homedir This will change the user's homedir. NOTE: This command is
     * disabled by default. To enable it, add "min_homedir /site" to your config
     * file, where "/site" is the minimum directory that users can have, i.e.
     * you can't change a user's home directory to /ftp-data or anything that
     * doesn't have "/site" at the beginning. Important: don't use a trailing
     * slash for homedir! Users CAN NOT cd, list, upload/download, etc, outside
     * of their home dir. It acts similarly to chroot() (try man chroot).
     * startup_dir The directory to start in. ex: /incoming will start the user
     * in /glftpd/site/incoming if rootpath is /glftpd and homedir is /site.
     * Users CAN cd, list, upload/download, etc, outside of startup_dir.
     * idle_time Sets the default and maximum idle time for this user (overrides
     * the -t and -T settings on glftpd command line). If -1, it is disabled; if
     * 0, it is the same as the idler flag. credits Credits left to download.
     * flags +1ABC or +H or -3, type "site flags" for a list of flags.
     * num_logins # # : number of simultaneous logins allowed. The second number
     * is number of sim. logins from the same IP. timeframe # # : the hour from
     * which to allow logins and the hour when logins from this user will start
     * being rejected. This is set in a 24 hour format. If a user is online past
     * his timeframe, he'll be disconnected the next time he does a 'CWD'.
     * time_limit Time limits, per LOGIN SESSION. (set in minutes. 0 =
     * Unlimited) tagline User's tagline. group_slots Number of users a GADMIN
     * is allowed to add. If you specify a second argument, it will be the
     * number of leech accounts the gadmin can give (done by "site change user
     * ratio 0") (2nd arg = leech slots) comment Changes the user's comment (max
     * 50 characters). Comments are displayed by the comment cookie (see below).
     * max_dlspeed Downstream bandwidth control (KBytes/sec) (0 = Unlimited)
     * max_ulspeed Same but for uploads max_sim_down Maximum number of
     * simultaneous downloads for this user (-1 = unlimited, 0 = zero [user
     * can't download]) max_sim_up Maximum number of simultaneous uploads for
     * this user (-1 = unlimited, 0 = zero [user can't upload]) sratio
     * <SECTIONNAME><#>This is to change the ratio of a section (other than
     * default).
     *
     * Flags available:
     *
     * Flagname Flag Description
     * ------------------------------------------------------------- SITEOP 1
     * User is siteop. GADMIN 2 User is Groupadmin of his/her first public group
     * (doesn't work for private groups). GLOCK 3 User cannot change group.
     * EXEMPT 4 Allows to log in when site is full. Also allows user to do "site
     * idle 0", which is the same as having the idler flag. Also exempts the
     * user from the sim_xfers limit in config file. COLOR 5 Enable/Disable the
     * use of color (toggle with "site color"). DELETED 6 User is deleted.
     * USEREDIT 7 "Co-Siteop" ANON 8 User is anonymous (per-session like login).
     *
     * NOTE* The 1 flag is not GOD mode, you must have the correct flags for the
     * actions you wish to perform. NOTE* If you have flag 1 then you DO NOT
     * WANT flag 2
     *
     * Restrictions placed on users flagged ANONYMOUS. 1. '!' on login is
     * ignored. 2. They cannot DELETE, RMDIR, or RENAME. 3. Userfiles do not
     * update like usual, meaning no stats will be kept for these users. The
     * userfile only serves as a template for the starting environment of the
     * logged in user. Use external scripts if you must keep records of their
     * transfer stats.
     *
     * NUKE A User is allowed to use site NUKE. UNNUKE B User is allowed to use
     * site UNNUKE. UNDUPE C User is allowed to use site UNDUPE. KICK D User is
     * allowed to use site KICK. KILL E User is allowed to use site KILL/SWHO.
     * TAKE F User is allowed to use site TAKE. GIVE G User is allowed to use
     * site GIVE. USERS/USER H This allows you to view users ( site USER/USERS )
     * IDLER I User is allowed to idle forever. CUSTOM1 J Custom flag 1 CUSTOM2
     * K Custom flag 2 CUSTOM3 L Custom flag 3 CUSTOM4 M Custom flag 4 CUSTOM5 N
     * Custom flag 5
     *
     * You can use custom flags in the config file to give some users access to
     * certain things without having to use private groups. These flags will
     * only show up in "site flags" if they're turned on.
     *
     * ex. site change Archimede ratio 5
     *
     * This would set the ratio to 1:5 for the user 'Archimede'.
     *
     * ex. site change Archimede flags +2-AG
     *
     * This would make the user 'Archimede' groupadmin and remove his ability to
     * use the commands site nuke and site give.
     *
     * NOTE: The flag DELETED can not be changed with site change, it will
     * change when someone does a site deluser/readd.
     */
    private FtpReply doSITE_CHANGE(BaseFtpConnection conn) {
        FtpReply usage = (FtpReply) FtpReply.RESPONSE_501_SYNTAX_ERROR.clone();
        usage.addComment(conn.jprintf(UserManagment.class, "change.usage"));

        FtpRequest request = conn.getRequest();

        if (!conn.getUserNull().isAdmin() &&
                !conn.getUserNull().isGroupAdmin()) {
            return FtpReply.RESPONSE_530_ACCESS_DENIED;
        }

        if (!request.hasArgument()) {
            return usage;
        }

        User userToChange;
        FtpReply response = (FtpReply) FtpReply.RESPONSE_200_COMMAND_OK.clone();
        ReplacerEnvironment env = new ReplacerEnvironment();

        StringTokenizer arguments = new StringTokenizer(request.getArgument());

        if (!arguments.hasMoreTokens()) {
            return usage;
        }

        String username = arguments.nextToken();

        try {
            userToChange = conn.getGlobalContext().getUserManager()
                               .getUserByName(username);
        } catch (NoSuchUserException e) {
            return new FtpReply(550,
                "User " + username + " not found: " + e.getMessage());
        } catch (UserFileException e) {
            logger.log(Level.ERROR, "Error loading user", e);

            return new FtpReply(550, "Error loading user: " + e.getMessage());
        }

        if (!arguments.hasMoreTokens()) {
            return usage;
        }

        String command = arguments.nextToken().toLowerCase();

        if (conn.getUserNull().isGroupAdmin() && !command.equals("ratio")) {
            return FtpReply.RESPONSE_530_ACCESS_DENIED;
        }

        env.add("targetuser", userToChange.getUsername());

        //		String args[] = request.getArgument().split(" ");
        //		String command = args[1].toLowerCase();
        // 0 = user
        // 1 = command
        // 2- = argument
        String[] commandArguments = new String[arguments.countTokens()];
        String fullCommandArgument = new String();

        for (int x = 0; arguments.hasMoreTokens(); x++) {
            commandArguments[x] = arguments.nextToken();
            fullCommandArgument = fullCommandArgument + " " +
                commandArguments[x];
        }

        fullCommandArgument = fullCommandArgument.trim();

        if ("ratio".equals(command)) {
            ////// Ratio //////
            if (commandArguments.length != 1) {
                return usage;
            }

            float ratio = Float.parseFloat(commandArguments[0]);

            if (conn.getUserNull().isGroupAdmin() &&
                    !conn.getUserNull().isAdmin()) {
                ////// Group Admin Ratio //////
                if (!conn.getUserNull().getGroupName().equals(userToChange.getGroupName())) {
                    return FtpReply.RESPONSE_530_ACCESS_DENIED;
                }

                if (ratio == 0F) {
                    int usedleechslots = 0;

                    try {
                        for (Iterator iter = conn.getGlobalContext()
                                                 .getUserManager()
                                                 .getAllUsersByGroup(conn.getUserNull()
                                                                         .getGroupName())
                                                 .iterator(); iter.hasNext();) {
                            if (((User) iter.next()).getRatio() == 0F) {
                                usedleechslots++;
                            }
                        }
                    } catch (UserFileException e1) {
                        return new FtpReply(200,
                            "IO error reading userfiles: " + e1.getMessage());
                    }

                    if (usedleechslots >= conn.getUserNull().getGroupLeechSlots()) {
                        return new FtpReply(200,
                            conn.jprintf(UserManagment.class,
                                "changeratio.nomoreslots"));
                    }
                } else if (ratio != 0F) {
                    return new FtpReply(200,
                        conn.jprintf(UserManagment.class,
                            "changeratio.invalidratio"));
                }

                logger.info("'" + conn.getUserNull().getUsername() +
                    "' changed ratio for '" + userToChange.getUsername() +
                    "' from '" + userToChange.getRatio() + "' to '" + ratio +
                    "'");
                userToChange.setRatio(ratio);
                env.add("newratio", Float.toString(userToChange.getRatio()));
                response.addComment(conn.jprintf(UserManagment.class,
                        "changeratio.success", env));
            } else {
                // Ratio changes by an admin //
                logger.info("'" + conn.getUserNull().getUsername() +
                    "' changed ratio for '" + userToChange.getUsername() +
                    "' from '" + userToChange.getRatio() + " to '" + ratio +
                    "'");
                userToChange.setRatio(ratio);
                env.add("newratio", Float.toString(userToChange.getRatio()));
                response.addComment(conn.jprintf(UserManagment.class,
                        "changeratio.success", env));
            }
        } else if ("credits".equals(command)) {
            if (commandArguments.length != 1) {
                return usage;
            }

            long credits = Bytes.parseBytes(commandArguments[0]);
            logger.info("'" + conn.getUserNull().getUsername() +
                "' changed credits for '" + userToChange.getUsername() +
                "' from '" + userToChange.getCredits() + " to '" + credits +
                "'");
            userToChange.setCredits(credits);
            env.add("newcredits", Bytes.formatBytes(userToChange.getCredits()));
            response.addComment(conn.jprintf(UserManagment.class,
                    "changecredits.success", env));
        } else if ("comment".equals(command)) {
            logger.info("'" + conn.getUserNull().getUsername() +
                "' changed comment for '" + userToChange.getUsername() +
                "' from '" + userToChange.getComment() + " to '" +
                fullCommandArgument + "'");
            userToChange.setComment(fullCommandArgument);
            env.add("comment", userToChange.getComment());
            response.addComment(conn.jprintf(UserManagment.class,
                    "changecomment.success", env));
        } else if ("idle_time".equals(command)) {
            if (commandArguments.length != 1) {
                return usage;
            }

            int idleTime = Integer.parseInt(commandArguments[0]);
            logger.info("'" + conn.getUserNull().getUsername() +
                "' changed idle_time for '" + userToChange.getUsername() +
                "' from '" + userToChange.getIdleTime() + " to '" + idleTime +
                "'");
            userToChange.setIdleTime(idleTime);
            env.add("idletime", Long.toString(userToChange.getIdleTime()));
            response.addComment(conn.jprintf(UserManagment.class,
                    "changeidletime.success", env));
        } else if ("num_logins".equals(command)) {
            // [# sim logins] [# sim logins/ip]
            try {
                int numLogins;
                int numLoginsIP;

                if ((commandArguments.length < 1) ||
                        (commandArguments.length > 2)) {
                    return FtpReply.RESPONSE_501_SYNTAX_ERROR;
                }

                numLogins = Integer.parseInt(commandArguments[0]);

                if (commandArguments.length == 2) {
                    numLoginsIP = Integer.parseInt(commandArguments[1]);
                } else {
                    numLoginsIP = userToChange.getMaxLoginsPerIP();
                }

                logger.info("'" + conn.getUserNull().getUsername() +
                    "' changed num_logins for '" + userToChange.getUsername() +
                    "' from '" + userToChange.getMaxLogins() + "' '" +
                    userToChange.getMaxLoginsPerIP() + "' to '" + numLogins +
                    "' '" + numLoginsIP + "'");
                userToChange.setMaxLogins(numLogins);
                userToChange.setMaxLoginsPerIP(numLoginsIP);
                env.add("numlogins", Long.toString(userToChange.getMaxLogins()));
                env.add("numloginsip",
                    Long.toString(userToChange.getMaxLoginsPerIP()));
                response.addComment(conn.jprintf(UserManagment.class,
                        "changenumlogins.success", env));
            } catch (NumberFormatException ex) {
                return FtpReply.RESPONSE_501_SYNTAX_ERROR;
            }

            //} else if ("max_dlspeed".equalsIgnoreCase(command)) {
            //	myUser.setMaxDownloadRate(Integer.parseInt(commandArgument));
            //} else if ("max_ulspeed".equals(command)) {
            //	myUser.setMaxUploadRate(Integer.parseInt(commandArgument));
        } else if ("group".equals(command)) {
            if (commandArguments.length != 1) {
                return usage;
            }

            logger.info("'" + conn.getUserNull().getUsername() +
                "' changed primary group for '" + userToChange.getUsername() +
                "' from '" + userToChange.getGroupName() + "' to '" +
                commandArguments[0] + "'");
            userToChange.setGroup(commandArguments[0]);
            env.add("primgroup", userToChange.getGroupName());
            response.addComment(conn.jprintf(UserManagment.class,
                    "changeprimgroup.success", env));

            //			group_slots Number of users a GADMIN is allowed to add.
            //					If you specify a second argument, it will be the
            //					number of leech accounts the gadmin can give (done by
            //					"site change user ratio 0") (2nd arg = leech slots)
        } else if ("group_slots".equals(command)) {
            try {
                if ((commandArguments.length < 1) ||
                        (commandArguments.length > 2)) {
                    return FtpReply.RESPONSE_501_SYNTAX_ERROR;
                }

                short groupSlots = Short.parseShort(commandArguments[0]);
                short groupLeechSlots;

                if (commandArguments.length >= 2) {
                    groupLeechSlots = Short.parseShort(commandArguments[1]);
                } else {
                    groupLeechSlots = userToChange.getGroupLeechSlots();
                }

                logger.info("'" + conn.getUserNull().getUsername() +
                    "' changed group_slots for '" + userToChange.getUsername() +
                    "' from '" + userToChange.getGroupSlots() + "' " +
                    userToChange.getGroupLeechSlots() + "' to '" + groupSlots +
                    "' '" + groupLeechSlots + "'");
                userToChange.setGroupSlots(groupSlots);
                userToChange.setGroupLeechSlots(groupLeechSlots);
                env.add("groupslots", "" + userToChange.getGroupSlots());
                env.add("groupleechslots",
                    Long.toString(userToChange.getGroupLeechSlots()));
                response.addComment(conn.jprintf(UserManagment.class,
                        "changegroupslots.success", env));
            } catch (NumberFormatException ex) {
                return FtpReply.RESPONSE_501_SYNTAX_ERROR;
            }
        } else if ("created".equals(command)) {
            long myDate;

            if (commandArguments.length == 0) {
                try {
                    myDate = new SimpleDateFormat("yyyy-MM-dd").parse(commandArguments[0])
                                                               .getTime();
                } catch (ParseException e1) {
                    logger.log(Level.INFO, e1);

                    return new FtpReply(200, e1.getMessage());
                }
            } else {
                myDate = System.currentTimeMillis();
            }

            logger.info("'" + conn.getUserNull().getUsername() +
                "' changed created for '" + userToChange.getUsername() +
                "' from '" + new Date(userToChange.getCreated()) + "' to '" +
                new Date(myDate) + "'");
            userToChange.setCreated(myDate);
            env.add("created", new Date(myDate));

            response = new FtpReply(200,
                    conn.jprintf(UserManagment.class, "changecreated.success",
                        env));
        } else if ("wkly_allotment".equals(command)) {
            if (commandArguments.length != 1) {
                return usage;
            }

            long weeklyAllotment = Bytes.parseBytes(commandArguments[0]);
            logger.info("'" + conn.getUserNull().getUsername() +
                "' changed wkly_allotment for '" + userToChange.getUsername() +
                "' from '" + userToChange.getWeeklyAllotment() + "' to " +
                weeklyAllotment + "'");
            userToChange.setWeeklyAllotment(weeklyAllotment);

            response = FtpReply.RESPONSE_200_COMMAND_OK;
        } else if ("tagline".equals(command)) {
            if (commandArguments.length < 1) {
                return usage;
            }

            logger.info("'" + conn.getUserNull().getUsername() +
                "' changed tagline for '" + userToChange.getUsername() +
                "' from '" + userToChange.getObject(TAGLINE, "") + "' to '" +
                fullCommandArgument + "'");
            userToChange.setTagline(fullCommandArgument);

            response = FtpReply.RESPONSE_200_COMMAND_OK;
        } else {
            return usage;
        }

        try {
            userToChange.commit();
        } catch (UserFileException e) {
            logger.warn("", e);
            response.addComment(e.getMessage());
        }

        return response;
    }

    /**
     * USAGE: site chgrp <user><group>[ <group>] Adds/removes a user from
     * group(s).
     *
     * ex. site chgrp archimede ftp This would change the group to 'ftp' for the
     * user 'archimede'.
     *
     * ex1. site chgrp archimede ftp This would remove the group ftp from the
     * user 'archimede'.
     *
     * ex2. site chgrp archimede ftp eleet This moves archimede from ftp group
     * to eleet group.
     */
    private FtpReply doSITE_CHGRP(BaseFtpConnection conn) {
        FtpRequest request = conn.getRequest();

        if (!conn.getUserNull().isAdmin()) {
            return FtpReply.RESPONSE_530_ACCESS_DENIED;
        }

        if (!request.hasArgument()) {
            return new FtpReply(501,
                conn.jprintf(UserManagment.class, "chgrp.usage"));
        }

        String[] args = request.getArgument().split("[ ,]");

        if (args.length < 2) {
            return FtpReply.RESPONSE_501_SYNTAX_ERROR;
        }

        User myUser;

        try {
            myUser = conn.getGlobalContext().getUserManager().getUserByName(args[0]);
        } catch (NoSuchUserException e) {
            return new FtpReply(200, "User not found: " + e.getMessage());
        } catch (UserFileException e) {
            logger.log(Level.FATAL, "IO error reading user", e);

            return new FtpReply(200, "IO error reading user: " +
                e.getMessage());
        }

        FtpReply response = new FtpReply(200);

        for (int i = 1; i < args.length; i++) {
            String string = args[i];

            try {
                myUser.removeSecondaryGroup(string);
                logger.info("'" + conn.getUserNull().getUsername() +
                    "' removed '" + myUser.getUsername() + "' from group '" +
                    string + "'");
                response.addComment(myUser.getUsername() +
                    " removed from group " + string);
            } catch (NoSuchFieldException e1) {
                try {
                    myUser.addSecondaryGroup(string);
                    logger.info("'" + conn.getUserNull().getUsername() +
                        "' added '" + myUser.getUsername() + "' to group '" +
                        string + "'");
                    response.addComment(myUser.getUsername() +
                        " added to group " + string);
                } catch (DuplicateElementException e2) {
                    throw new RuntimeException("Error, user was not a member before",
                        e2);
                }
            }
        }

        return response;
    }

    /**
     * USAGE: site chpass <user><password>Change users password.
     *
     * ex. site chpass Archimede newpassword This would change the password to
     * 'newpassword' for the user 'Archimede'.
     *
     * See "site passwd" for more info if you get a "Password is not secure
     * enough" error.
     *  * Denotes any password, ex. site chpass arch * This will allow arch to
     * login with any password
     *  @ Denotes any email-like password, ex. site chpass arch @ This will
     * allow arch to login with a@b.com but not ab.com
     */
    private FtpReply doSITE_CHPASS(BaseFtpConnection conn) {
        FtpRequest request = conn.getRequest();

        if (!conn.getUserNull().isAdmin()) {
            return FtpReply.RESPONSE_530_ACCESS_DENIED;
        }

        if (!request.hasArgument()) {
            return new FtpReply(501,
                conn.jprintf(UserManagment.class, "chpass.usage"));
        }

        String[] args = request.getArgument().split(" ");

        if (args.length != 2) {
            return FtpReply.RESPONSE_501_SYNTAX_ERROR;
        }

        try {
            User myUser = conn.getGlobalContext().getUserManager()
                              .getUserByName(args[0]);
            myUser.setPassword(args[1]);
            logger.info("'" + conn.getUserNull().getUsername() +
                "' changed password for '" + myUser.getUsername() + "'");

            return FtpReply.RESPONSE_200_COMMAND_OK;
        } catch (NoSuchUserException e) {
            return new FtpReply(200, "User not found: " + e.getMessage());
        } catch (UserFileException e) {
            logger.log(Level.FATAL, "Error reading userfile", e);

            return new FtpReply(200, "Error reading userfile: " +
                e.getMessage());
        }
    }

    /**
     * USAGE: site delip <user><ident@ip>...
     *
     * @param request
     * @param out
     */
    private FtpReply doSITE_DELIP(BaseFtpConnection conn) {
        FtpRequest request = conn.getRequest();

        if (!conn.getUserNull().isAdmin() &&
                !conn.getUserNull().isGroupAdmin()) {
            return FtpReply.RESPONSE_530_ACCESS_DENIED;
        }

        if (!request.hasArgument()) {
            return new FtpReply(501,
                conn.jprintf(UserManagment.class, "delip.usage"));
        }

        String[] args = request.getArgument().split(" ");

        if (args.length < 2) {
            return FtpReply.RESPONSE_501_SYNTAX_ERROR;
        }

        User myUser;

        try {
            myUser = conn.getGlobalContext().getUserManager().getUserByName(args[0]);
        } catch (NoSuchUserException e) {
            return new FtpReply(200, e.getMessage());
        } catch (UserFileException e) {
            logger.log(Level.FATAL, "IO error", e);

            return new FtpReply(200, "IO error: " + e.getMessage());
        }

        if (conn.getUserNull().isGroupAdmin() &&
                !conn.getUserNull().getGroupName().equals(myUser.getGroupName())) {
            return FtpReply.RESPONSE_530_ACCESS_DENIED;
        }

        FtpReply response = new FtpReply(200);

        for (int i = 1; i < args.length; i++) {
            String string = args[i];

            try {
                myUser.removeIpMask(string);
                logger.info("'" + conn.getUserNull().getUsername() +
                    "' removed ip '" + string + "' from '" + myUser + "'");
                response.addComment("Removed " + string);
            } catch (NoSuchFieldException e1) {
                response.addComment("Mask " + string + " not found: " +
                    e1.getMessage());

                continue;
            }
        }

        return response;
    }

    private FtpReply doSITE_DELUSER(BaseFtpConnection conn) {
        FtpRequest request = conn.getRequest();

        if (!request.hasArgument()) {
            return new FtpReply(501,
                conn.jprintf(UserManagment.class, "deluser.usage"));
        }

        if (!conn.getUserNull().isAdmin() &&
                !conn.getUserNull().isGroupAdmin()) {
            return FtpReply.RESPONSE_530_ACCESS_DENIED;
        }

        String delUsername = request.getArgument();
        User myUser;

        try {
            myUser = conn.getGlobalContext().getUserManager().getUserByName(delUsername);
        } catch (NoSuchUserException e) {
            return new FtpReply(200, e.getMessage());
        } catch (UserFileException e) {
            return new FtpReply(200, "Couldn't getUser: " + e.getMessage());
        }

        if (conn.getUserNull().isGroupAdmin() &&
                !conn.getUserNull().getGroupName().equals(myUser.getGroupName())) {
            return FtpReply.RESPONSE_530_ACCESS_DENIED;
        }

        myUser.setDeleted(true);
        logger.info("'" + conn.getUserNull().getUsername() +
            "' deleted user '" + myUser.getUsername() + "'");

        return FtpReply.RESPONSE_200_COMMAND_OK;
    }

    private FtpReply doSITE_GINFO(BaseFtpConnection conn) {
        FtpRequest request = conn.getRequest();

        //security
        if (!conn.getUserNull().isAdmin() &&
                !conn.getUserNull().isGroupAdmin()) {
            return FtpReply.RESPONSE_530_ACCESS_DENIED;
        }

        //syntax
        if (!request.hasArgument()) {
            return new FtpReply(501,
                conn.jprintf(UserManagment.class, "ginfo.usage"));
        }

        //gadmin
        String group = request.getArgument();

        if (conn.getUserNull().isGroupAdmin() &&
                !conn.getUserNull().getGroupName().equals(group)) {
            return FtpReply.RESPONSE_530_ACCESS_DENIED;
        }

        Collection users;

        try {
            users = conn.getGlobalContext().getUserManager().getAllUsersByGroup(group);
        } catch (UserFileException e) {
            return new FtpReply(200, "IO error: " + e.getMessage());
        }

        FtpReply response = new FtpReply(200);

        for (Iterator iter = users.iterator(); iter.hasNext();) {
            User user = (User) iter.next();
            char status = ' ';

            if (user.isGroupAdmin()) {
                status = '+';
            } else if (user.isAdmin()) {
                status = '*';
            }

            response.addComment(status + user.getUsername());
        }

        response.addComment(" * = siteop   + = gadmin");

        return response;
    }

    private FtpReply doSITE_GIVE(BaseFtpConnection conn) {
        FtpRequest request = conn.getRequest();

        if (!conn.getGlobalContext().getConfig().checkGive(conn.getUserNull())) {
            return FtpReply.RESPONSE_530_ACCESS_DENIED;
        }

        if (!request.hasArgument()) {
            return new FtpReply(501,
                conn.jprintf(UserManagment.class, "give.usage"));
        }

        StringTokenizer st = new StringTokenizer(request.getArgument());

        if (!st.hasMoreTokens()) {
            return FtpReply.RESPONSE_501_SYNTAX_ERROR;
        }

        User myUser;

        try {
            myUser = conn.getGlobalContext().getUserManager().getUserByName(st.nextToken());
        } catch (Exception e) {
            logger.warn("", e);

            return new FtpReply(200, e.getMessage());
        }

        if (!st.hasMoreTokens()) {
            return FtpReply.RESPONSE_501_SYNTAX_ERROR;
        }

        long credits = Bytes.parseBytes(st.nextToken());

        if (0 > credits) {
            return new FtpReply(200, credits + " is not a positive number.");
        }

        if (!conn.getUserNull().isAdmin()) {
            if (credits > conn.getUserNull().getCredits()) {
                return new FtpReply(200,
                    "You cannot give more credits than you have.");
            }

            conn.getUserNull().updateCredits(-credits);
        }

        logger.info("'" + conn.getUserNull().getUsername() + "' transfered " +
            Bytes.formatBytes(credits) + " ('" + credits + "') to '" +
            myUser.getUsername() + "'");
        myUser.updateCredits(credits);

        return new FtpReply(200,
            "OK, gave " + Bytes.formatBytes(credits) + " of your credits to " +
            myUser.getUsername());
    }

    private FtpReply doSITE_GROUPS(BaseFtpConnection conn) {
        Collection groups;

        try {
            groups = conn.getGlobalContext().getUserManager().getAllGroups();
        } catch (UserFileException e) {
            logger.log(Level.FATAL, "IO error from getAllGroups()", e);

            return new FtpReply(200, "IO error: " + e.getMessage());
        }

        FtpReply response = new FtpReply(200);
        response.addComment("All groups:");

        for (Iterator iter = groups.iterator(); iter.hasNext();) {
            String element = (String) iter.next();
            response.addComment(element);
        }

        return response;
    }

    private FtpReply doSITE_GRPREN(BaseFtpConnection conn) {
        FtpRequest request = conn.getRequest();

        if (!conn.getUserNull().isAdmin()) {
            return FtpReply.RESPONSE_530_ACCESS_DENIED;
        }

        if (!request.hasArgument()) {
            return new FtpReply(501,
                conn.jprintf(UserManagment.class, "grpren.usage"));
        }

        StringTokenizer st = new StringTokenizer(request.getArgument());

        if (!st.hasMoreTokens()) {
            return new FtpReply(501,
                conn.jprintf(UserManagment.class, "grpren.usage"));
        }

        String oldGroup = st.nextToken();

        if (!st.hasMoreTokens()) {
            return new FtpReply(501,
                conn.jprintf(UserManagment.class, "grpren.usage"));
        }

        String newGroup = st.nextToken();
        Collection users = null;

        try {
            if (!conn.getGlobalContext().getUserManager()
                         .getAllUsersByGroup(newGroup).isEmpty()) {
                return new FtpReply(500, newGroup + " already exists");
            }

            users = conn.getGlobalContext().getUserManager().getAllUsersByGroup(oldGroup);
        } catch (UserFileException e) {
            logger.log(Level.FATAL,
                "IO error from getAllUsersByGroup(" + oldGroup + ")", e);

            return new FtpReply(200, "IO error: " + e.getMessage());
        }

        FtpReply response = new FtpReply(200);
        response.addComment("Renaming group " + oldGroup + " to " + newGroup);

        for (Iterator iter = users.iterator(); iter.hasNext();) {
            User userToChange = (User) iter.next();

            if (userToChange.getGroupName().equals(oldGroup)) {
                userToChange.setGroup(newGroup);
            } else {
                try {
                    userToChange.removeSecondaryGroup(oldGroup);
                } catch (NoSuchFieldException e1) {
                    throw new RuntimeException(
                        "User was not in group returned by getAllUsersByGroup");
                }

                try {
                    userToChange.addSecondaryGroup(newGroup);
                } catch (DuplicateElementException e2) {
                    throw new RuntimeException("group " + newGroup +
                        " already exists");
                }
            }

            response.addComment("Changed user " + userToChange.getUsername());
        }

        return response;
    }

    private FtpReply doSITE_KICK(BaseFtpConnection conn) {
        FtpRequest request = conn.getRequest();

        if (!conn.getUserNull().isAdmin()) {
            return FtpReply.RESPONSE_530_ACCESS_DENIED;
        }

        if (!request.hasArgument()) {
            return new FtpReply(501,
                conn.jprintf(UserManagment.class, "kick.usage"));
        }

        String arg = request.getArgument();
        int pos = arg.indexOf(' ');
        String username;
        String message = "Kicked by " + conn.getUserNull().getUsername();

        if (pos == -1) {
            username = arg;
        } else {
            username = arg.substring(0, pos);
            message = arg.substring(pos + 1);
        }

        FtpReply response = (FtpReply) FtpReply.RESPONSE_200_COMMAND_OK.clone();
        ArrayList conns = new ArrayList(conn.getGlobalContext()
                                            .getConnectionManager()
                                            .getConnections());

        for (Iterator iter = conns.iterator(); iter.hasNext();) {
            BaseFtpConnection conn2 = (BaseFtpConnection) iter.next();

            try {
                if (conn2.getUser().getUsername().equals(username)) {
                    conn2.stop(message);
                }
            } catch (NoSuchUserException e) {
            }
        }

        return response;
    }

    private FtpReply doSITE_PASSWD(BaseFtpConnection conn) {
        FtpRequest request = conn.getRequest();

        if (!request.hasArgument()) {
            return new FtpReply(501,
                conn.jprintf(UserManagment.class, "passwd.usage"));
        }

        logger.info("'" + conn.getUserNull().getUsername() +
            "' changed his password");
        conn.getUserNull().setPassword(request.getArgument());

        return FtpReply.RESPONSE_200_COMMAND_OK;
    }

    private FtpReply doSITE_PURGE(BaseFtpConnection conn) {
        FtpRequest request = conn.getRequest();

        if (!conn.getUserNull().isAdmin() &&
                !conn.getUserNull().isGroupAdmin()) {
            return FtpReply.RESPONSE_530_ACCESS_DENIED;
        }

        if (!request.hasArgument()) {
            return new FtpReply(501,
                conn.jprintf(UserManagment.class, "purge.usage"));
        }

        String delUsername = request.getArgument();
        User myUser;

        try {
            myUser = conn.getGlobalContext().getUserManager()
                         .getUserByNameUnchecked(delUsername);
        } catch (NoSuchUserException e) {
            return new FtpReply(200, e.getMessage());
        } catch (UserFileException e) {
            return new FtpReply(200, "Couldn't getUser: " + e.getMessage());
        }

        if (!myUser.isDeleted()) {
            return new FtpReply(200, "User isn't deleted");
        }

        if (conn.getUserNull().isGroupAdmin() &&
                !conn.getUserNull().getGroupName().equals(myUser.getGroupName())) {
            return FtpReply.RESPONSE_530_ACCESS_DENIED;
        }

        myUser.purge();
        logger.info("'" + conn.getUserNull().getUsername() + "' purged '" +
            myUser.getUsername() + "'");

        return FtpReply.RESPONSE_200_COMMAND_OK;
    }

    private FtpReply doSITE_READD(BaseFtpConnection conn) {
        FtpRequest request = conn.getRequest();

        if (!conn.getUserNull().isAdmin() &&
                !conn.getUserNull().isGroupAdmin()) {
            return FtpReply.RESPONSE_530_ACCESS_DENIED;
        }

        if (!request.hasArgument()) {
            return new FtpReply(501,
                conn.jprintf(UserManagment.class, "readd.usage"));
        }

        User myUser;

        try {
            myUser = conn.getGlobalContext().getUserManager()
                         .getUserByNameUnchecked(request.getArgument());
        } catch (NoSuchUserException e) {
            return new FtpReply(200, e.getMessage());
        } catch (UserFileException e) {
            return new FtpReply(200, "IO error: " + e.getMessage());
        }

        if (conn.getUserNull().isGroupAdmin() &&
                !conn.getUserNull().getGroupName().equals(myUser.getGroupName())) {
            return FtpReply.RESPONSE_530_ACCESS_DENIED;
        }

        if (!myUser.isDeleted()) {
            return new FtpReply(200, "User wasn't deleted");
        }

        myUser.setDeleted(false);
        logger.info("'" + conn.getUserNull().getUsername() + "' readded '" +
            myUser.getUsername() + "'");

        return FtpReply.RESPONSE_200_COMMAND_OK;
    }

    private FtpReply doSITE_RENUSER(BaseFtpConnection conn) {
        FtpRequest request = conn.getRequest();

        if (!conn.getUserNull().isAdmin()) {
            return FtpReply.RESPONSE_530_ACCESS_DENIED;
        }

        if (!request.hasArgument()) {
            return new FtpReply(501,
                conn.jprintf(UserManagment.class, "renuser.usage"));
        }

        String[] args = request.getArgument().split(" ");

        if (args.length != 2) {
            return FtpReply.RESPONSE_501_SYNTAX_ERROR;
        }

        try {
            User myUser = conn.getGlobalContext().getUserManager()
                              .getUserByName(args[0]);
            String oldUsername = myUser.getUsername();
            myUser.rename(args[1]);
            logger.info("'" + conn.getUserNull().getUsername() + "' renamed '" +
                oldUsername + "' to '" + myUser.getUsername() + "'");
        } catch (NoSuchUserException e) {
            return new FtpReply(200, "No such user: " + e.getMessage());
        } catch (UserExistsException e) {
            return new FtpReply(200, "Target username is already taken");
        } catch (UserFileException e) {
            return new FtpReply(200, e.getMessage());
        }

        return FtpReply.RESPONSE_200_COMMAND_OK;
    }

    private FtpReply doSITE_SEEN(BaseFtpConnection conn) {
        FtpRequest request = conn.getRequest();

        if (!request.hasArgument()) {
            return new FtpReply(501,
                conn.jprintf(UserManagment.class, "seen.usage"));
        }

        User user;

        try {
            user = conn.getGlobalContext().getUserManager().getUserByName(request.getArgument());
        } catch (NoSuchUserException e) {
            return new FtpReply(200, e.getMessage());
        } catch (UserFileException e) {
            logger.log(Level.FATAL, "", e);

            return new FtpReply(200, "Error reading userfile: " +
                e.getMessage());
        }

        return new FtpReply(200,
            "User was last seen: " + new Date(user.getLastAccessTime()));
    }

    private FtpReply doSITE_TAGLINE(BaseFtpConnection conn) {
        FtpRequest request = conn.getRequest();

        if (!request.hasArgument()) {
            return new FtpReply(501,
                conn.jprintf(UserManagment.class, "tagline.usage"));
        }

        logger.info("'" + conn.getUserNull().getUsername() +
            "' changed his tagline from '" +
            conn.getUserNull().getObject(TAGLINE, "") + "' to '" +
            request.getArgument() + "'");
        conn.getUserNull().setTagline(request.getArgument());

        return FtpReply.RESPONSE_200_COMMAND_OK;
    }

    /**
     * USAGE: site take <user><kbytes>[ <message>] Removes credit from user
     *
     * ex. site take Archimede 100000 haha
     *
     * This will remove 100mb of credits from the user 'Archimede' and send the
     * message haha to him.
     */
    private FtpReply doSITE_TAKE(BaseFtpConnection conn) {
        FtpRequest request = conn.getRequest();

        if (!conn.getGlobalContext().getConfig().checkTake(conn.getUserNull())) {
            return FtpReply.RESPONSE_530_ACCESS_DENIED;
        }

        if (!request.hasArgument()) {
            return new FtpReply(501,
                conn.jprintf(UserManagment.class, "take.usage"));
        }

        StringTokenizer st = new StringTokenizer(request.getArgument());

        if (!st.hasMoreTokens()) {
            return FtpReply.RESPONSE_501_SYNTAX_ERROR;
        }

        User myUser;
        long credits;

        try {
            myUser = conn.getGlobalContext().getUserManager().getUserByName(st.nextToken());

            if (!st.hasMoreTokens()) {
                return FtpReply.RESPONSE_501_SYNTAX_ERROR;
            }

            credits = Bytes.parseBytes(st.nextToken()); // B, not KiB

            if (0 > credits) {
                return new FtpReply(200, "Credits must be a positive number.");
            }

            logger.info("'" + conn.getUserNull().getUsername() + "' took " +
                Bytes.formatBytes(credits) + " ('" + credits + "') from '" +
                myUser.getUsername() + "'");
            myUser.updateCredits(-credits);
        } catch (Exception ex) {
            return new FtpReply(200, ex.getMessage());
        }

        return new FtpReply(200,
            "OK, removed " + credits + "b from " + myUser.getUsername() + ".");
    }

    /**
     * USAGE: site user [ <user>] Lists users / Shows detailed info about a
     * user.
     *
     * ex. site user
     *
     * This will display a list of all users currently on site.
     *
     * ex. site user Archimede
     *
     * This will show detailed information about user 'Archimede'.
     */
    private FtpReply doSITE_USER(BaseFtpConnection conn) {
        FtpRequest request = conn.getRequest();

        if (!conn.getUserNull().isAdmin() &&
                !conn.getUserNull().isGroupAdmin()) {
            return FtpReply.RESPONSE_530_ACCESS_DENIED;
        }

        if (!request.hasArgument()) {
            return new FtpReply(501,
                conn.jprintf(UserManagment.class, "user.usage"));
        }

        FtpReply response = (FtpReply) FtpReply.RESPONSE_200_COMMAND_OK.clone();
        User myUser;

        try {
            myUser = conn.getGlobalContext().getUserManager()
                         .getUserByNameUnchecked(request.getArgument());
        } catch (NoSuchUserException ex) {
            response.setMessage("User " + request.getArgument() + " not found");

            return response;

            //return FtpResponse.RESPONSE_200_COMMAND_OK);
        } catch (UserFileException ex) {
            return new FtpReply(200, ex.getMessage());
        }

        if (conn.getUserNull().isGroupAdmin() &&
                !conn.getUserNull().getGroupName().equals(myUser.getGroupName())) {
            return FtpReply.RESPONSE_501_SYNTAX_ERROR;
        }

        //int i = (int) (myUser.getTimeToday() / 1000);
        //int hours = i / 60;
        //int minutes = i - hours * 60;
        //response.addComment("time on today: " + hours + ":" + minutes);
        //TODO replacerenvironment
        ReplacerEnvironment env = new ReplacerEnvironment();
        env.add("username", myUser.getUsername());
        env.add("created", new Date(myUser.getCreated()));
        env.add("comment", myUser.getComment());
        env.add("lastseen", new Date(myUser.getLastAccessTime()));
        env.add("totallogins", Long.toString(myUser.getLogins()));
        env.add("idletime", Long.toString(myUser.getIdleTime()));
        env.add("userratio", Float.toString(myUser.getRatio()));
        env.add("usercredits", Bytes.formatBytes(myUser.getCredits()));
        env.add("maxlogins", Long.toString(myUser.getMaxLogins()));
        env.add("maxloginsip", Long.toString(myUser.getMaxLoginsPerIP()));
        env.add("groupslots", Long.toString(myUser.getGroupSlots()));
        env.add("groupleechslots", Long.toString(myUser.getGroupLeechSlots()));
        env.add("useruploaded", Bytes.formatBytes(myUser.getUploadedBytes()));
        env.add("userdownloaded", Bytes.formatBytes(myUser.getDownloadedBytes()));

        //env.add("timesnuked", Long.toString(myUser.getTimesNuked()));
        //env.add("nukedbytes", Bytes.formatBytes(myUser.getNukedBytes()));
        env.add("primarygroup", myUser.getGroupName());
        env.add("extragroups", myUser.getGroups());
        env.add("ipmasks", myUser.getHostMaskCollection());
        env.add("wkly_allotment", Bytes.formatBytes(myUser.getWeeklyAllotment()));

        response.addComment(conn.jprintf(UserManagment.class, "user", env));

        return response;
    }

    private FtpReply doSITE_USERS(BaseFtpConnection conn) {
        FtpRequest request = conn.getRequest();

        if (!conn.getUserNull().isAdmin()) {
            return FtpReply.RESPONSE_530_ACCESS_DENIED;
        }

        FtpReply response = new FtpReply(200);
        Collection myUsers;

        try {
            myUsers = conn.getGlobalContext().getUserManager().getAllUsers();
        } catch (UserFileException e) {
            logger.log(Level.FATAL, "IO error reading all users", e);

            return new FtpReply(200, "IO error: " + e.getMessage());
        }

        if (request.hasArgument()) {
            Permission perm = new Permission(FtpConfig.makeUsers(
                        new StringTokenizer(request.getArgument())), true);

            for (Iterator iter = myUsers.iterator(); iter.hasNext();) {
                User element = (User) iter.next();

                if (!perm.check(element)) {
                    iter.remove();
                }
            }
        }

        for (Iterator iter = myUsers.iterator(); iter.hasNext();) {
            User myUser = (User) iter.next();
            response.addComment(myUser.getUsername());
        }

        response.addComment("Ok, " + myUsers.size() + " users listed.");

        return response;
    }

    /**
     * Lists currently connected users.
     */
    private FtpReply doSITE_WHO(BaseFtpConnection conn) {
        FtpReply response = (FtpReply) FtpReply.RESPONSE_200_COMMAND_OK.clone();
        long users = 0;
        long speedup = 0;
        long speeddn = 0;
        long speed = 0;

        try {
            ReplacerFormat formatup = ReplacerUtils.finalFormat(UserManagment.class,
                    "who.up");
            ReplacerFormat formatdown = ReplacerUtils.finalFormat(UserManagment.class,
                    "who.down");
            ReplacerFormat formatidle = ReplacerUtils.finalFormat(UserManagment.class,
                    "who.idle");
            ReplacerFormat formatcommand = ReplacerUtils.finalFormat(UserManagment.class,
                    "who.command");
            ReplacerEnvironment env = new ReplacerEnvironment();
            ArrayList conns = new ArrayList(conn.getGlobalContext()
                                                .getConnectionManager()
                                                .getConnections());

            for (Iterator iter = conns.iterator(); iter.hasNext();) {
                BaseFtpConnection conn2 = (BaseFtpConnection) iter.next();

                if (conn2.isAuthenticated()) {
                    users++;

                    User user;

                    try {
                        user = conn2.getUser();
                    } catch (NoSuchUserException e) {
                        continue;
                    }

                    if (conn.getGlobalContext().getConfig().checkHideInWho(user,
                                conn2.getCurrentDirectory())) {
                        continue;
                    }

                    //StringBuffer status = new StringBuffer();
                    env.add("idle",
                        Time.formatTime(System.currentTimeMillis() -
                            conn2.getLastActive()));
                    env.add("targetuser", user.getUsername());

                    if (!conn2.isExecuting()) {
                        response.addComment(SimplePrintf.jprintf(formatidle, env));
                    } else if (conn2.getDataConnectionHandler().isTransfering()) {
                        if (conn2.getDataConnectionHandler().isTransfering()) {
                            speed = conn2.getDataConnectionHandler()
                                         .getTransfer().getXferSpeed();
                            env.add("speed", Bytes.formatBytes(speed) + "/s");
                            env.add("file",
                                conn2.getDataConnectionHandler()
                                     .getTransferFile().getName());
                            env.add("slave",
                                conn2.getDataConnectionHandler()
                                     .getTranferSlave().getName());
                        }

                        if (conn2.getTransferDirection() == RemoteTransfer.TRANSFER_RECEIVING_UPLOAD) {
                            response.addComment(SimplePrintf.jprintf(formatup,
                                    env));
                            speedup += speed;
                        } else if (conn2.getTransferDirection() == RemoteTransfer.TRANSFER_SENDING_DOWNLOAD) {
                            response.addComment(SimplePrintf.jprintf(
                                    formatdown, env));
                            speeddn += speed;
                        }
                    } else {
                        env.add("command", conn2.getRequest().getCommand());
                        response.addComment(SimplePrintf.jprintf(
                                formatcommand, env));
                    }
                }
            }

            env.add("currentusers", Long.toString(users));
            env.add("maxusers",
                Long.toString(conn.getGlobalContext().getConfig()
                                  .getMaxUsersTotal()));
            env.add("totalupspeed", Bytes.formatBytes(speedup) + "/s");
            env.add("totaldnspeed", Bytes.formatBytes(speeddn) + "/s");
            response.addComment("");
            response.addComment(conn.jprintf(UserManagment.class,
                    "who.statusspeed", env));
            response.addComment(conn.jprintf(UserManagment.class,
                    "who.statususers", env));

            return response;
        } catch (FormatterException e) {
            return new FtpReply(200, e.getMessage());
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see net.sf.drftpd.master.command.CommandHandler#execute(net.sf.drftpd.master.BaseFtpConnection)
     */
    public FtpReply execute(BaseFtpConnection conn)
        throws UnhandledCommandException {
        String cmd = conn.getRequest().getCommand();

        if ("SITE ADDIP".equals(cmd)) {
            return doSITE_ADDIP(conn);
        }

        if ("SITE CHANGE".equals(cmd)) {
            return doSITE_CHANGE(conn);
        }

        if ("SITE CHGRP".equals(cmd)) {
            return doSITE_CHGRP(conn);
        }

        if ("SITE CHPASS".equals(cmd)) {
            return doSITE_CHPASS(conn);
        }

        if ("SITE DELIP".equals(cmd)) {
            return doSITE_DELIP(conn);
        }

        if ("SITE DELUSER".equals(cmd)) {
            return doSITE_DELUSER(conn);
        }

        if ("SITE ADDUSER".equals(cmd) || "SITE GADDUSER".equals(cmd)) {
            return doSITE_ADDUSER(conn);
        }

        if ("SITE GINFO".equals(cmd)) {
            return doSITE_GINFO(conn);
        }

        if ("SITE GIVE".equals(cmd)) {
            return doSITE_GIVE(conn);
        }

        if ("SITE GROUPS".equals(cmd)) {
            return doSITE_GROUPS(conn);
        }

        if ("SITE GRPREN".equals(cmd)) {
            return doSITE_GRPREN(conn);
        }

        if ("SITE KICK".equals(cmd)) {
            return doSITE_KICK(conn);
        }

        if ("SITE PASSWD".equals(cmd)) {
            return doSITE_PASSWD(conn);
        }

        if ("SITE PURGE".equals(cmd)) {
            return doSITE_PURGE(conn);
        }

        if ("SITE READD".equals(cmd)) {
            return doSITE_READD(conn);
        }

        if ("SITE RENUSER".equals(cmd)) {
            return doSITE_RENUSER(conn);
        }

        if ("SITE SEEN".equals(cmd)) {
            return doSITE_SEEN(conn);
        }

        if ("SITE TAGLINE".equals(cmd)) {
            return doSITE_TAGLINE(conn);
        }

        if ("SITE TAKE".equals(cmd)) {
            return doSITE_TAKE(conn);
        }

        if ("SITE USER".equals(cmd)) {
            return doSITE_USER(conn);
        }

        if ("SITE USERS".equals(cmd)) {
            return doSITE_USERS(conn);
        }

        if ("SITE WHO".equals(cmd)) {
            return doSITE_WHO(conn);
        }

        throw UnhandledCommandException.create(UserManagment.class,
            conn.getRequest());
    }

    public String[] getFeatReplies() {
        return null;
    }

    public CommandHandler initialize(BaseFtpConnection conn,
        CommandManager initializer) {
        return this;
    }

    public void load(CommandManagerFactory initializer) {
    }

    public void unload() {
    }
}