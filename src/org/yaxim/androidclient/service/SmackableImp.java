package org.yaxim.androidclient.service;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import org.jivesoftware.smack.AccountManager;
import org.jivesoftware.smack.Connection;
import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.ConnectionListener;
import org.jivesoftware.smack.PacketListener;
import org.jivesoftware.smack.Roster;
import org.jivesoftware.smack.RosterEntry;
import org.jivesoftware.smack.RosterGroup;
import org.jivesoftware.smack.RosterListener;
import org.jivesoftware.smack.SmackConfiguration;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.filter.PacketFilter;
import org.jivesoftware.smack.filter.PacketTypeFilter;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.IQ.Type;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Packet;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.packet.Presence.Mode;
import org.jivesoftware.smack.provider.ProviderManager;
import org.jivesoftware.smack.util.StringUtils;
import org.jivesoftware.smackx.Form;
import org.jivesoftware.smackx.FormField;
import org.jivesoftware.smackx.ServiceDiscoveryManager;
import org.jivesoftware.smackx.muc.DiscussionHistory;
import org.jivesoftware.smackx.muc.InvitationListener;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.muc.Occupant;
import org.jivesoftware.smackx.carbons.Carbon;
import org.jivesoftware.smackx.carbons.Carbon;
import org.jivesoftware.smackx.carbons.CarbonManager;
import org.jivesoftware.smackx.forward.Forwarded;
import org.jivesoftware.smackx.carbons.CarbonManager;
import org.jivesoftware.smackx.forward.Forwarded;
import org.jivesoftware.smackx.provider.DelayInfoProvider;
import org.jivesoftware.smackx.provider.DiscoverInfoProvider;
import org.jivesoftware.smackx.packet.DelayInformation;
import org.jivesoftware.smackx.packet.DelayInfo;
import org.jivesoftware.smackx.ping.PingManager;
import org.jivesoftware.smackx.ping.packet.*;
import org.jivesoftware.smackx.ping.PingManager;
import org.jivesoftware.smackx.ping.packet.*;
import org.jivesoftware.smackx.ping.provider.PingProvider;
import org.jivesoftware.smackx.ping.provider.PingProvider;
import org.jivesoftware.smackx.receipts.DeliveryReceipt;
import org.jivesoftware.smackx.receipts.DeliveryReceiptManager;
import org.jivesoftware.smackx.receipts.DeliveryReceiptRequest;
import org.yaxim.androidclient.YaximApplication;
import org.yaxim.androidclient.data.ChatProvider;
import org.yaxim.androidclient.data.RosterProvider;
import org.yaxim.androidclient.data.YaximConfiguration;
import org.yaxim.androidclient.data.ChatProvider.ChatConstants;
import org.yaxim.androidclient.data.RosterProvider.RosterConstants;
import org.yaxim.androidclient.exceptions.YaximXMPPException;
import org.yaxim.androidclient.util.LogConstants;
import org.yaxim.androidclient.util.PreferenceConstants;
import org.yaxim.androidclient.util.StatusMode;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;

import android.net.Uri;
import android.telephony.gsm.SmsMessage.MessageClass;
import android.util.Log;

public class SmackableImp implements Smackable {
	final static private String TAG = "yaxim.SmackableImp";

	final static private int PACKET_TIMEOUT = 30000;

	final static private String[] SEND_OFFLINE_PROJECTION = new String[] {
			ChatConstants._ID, ChatConstants.JID,
			ChatConstants.MESSAGE, ChatConstants.DATE, ChatConstants.PACKET_ID };
	final static private String SEND_OFFLINE_SELECTION =
			ChatConstants.DIRECTION + " = " + ChatConstants.OUTGOING + " AND " +
			ChatConstants.DELIVERY_STATUS + " = " + ChatConstants.DS_NEW;

	static {
		registerSmackProviders();
	}

	static void registerSmackProviders() {
		ProviderManager pm = ProviderManager.getInstance();
		// add IQ handling
		pm.addIQProvider("query","http://jabber.org/protocol/disco#info", new DiscoverInfoProvider());
		// add delayed delivery notifications
		pm.addExtensionProvider("delay","urn:xmpp:delay", new DelayInfoProvider());
		pm.addExtensionProvider("x","jabber:x:delay", new DelayInfoProvider());
		// add carbons and forwarding
		pm.addExtensionProvider("forwarded", Forwarded.NAMESPACE, new Forwarded.Provider());
		pm.addExtensionProvider("sent", Carbon.NAMESPACE, new Carbon.Provider());
		pm.addExtensionProvider("received", Carbon.NAMESPACE, new Carbon.Provider());
		// add delivery receipts
		pm.addExtensionProvider(DeliveryReceipt.ELEMENT, DeliveryReceipt.NAMESPACE, new DeliveryReceipt.Provider());
		pm.addExtensionProvider(DeliveryReceiptRequest.ELEMENT, DeliveryReceipt.NAMESPACE, new DeliveryReceiptRequest.Provider());
		// add XMPP Ping (XEP-0199)
		pm.addIQProvider("ping","urn:xmpp:ping", new PingProvider());

		ServiceDiscoveryManager.setIdentityName(YaximApplication.XMPP_IDENTITY_NAME);
		ServiceDiscoveryManager.setIdentityType(YaximApplication.XMPP_IDENTITY_TYPE);
	}

	private final YaximConfiguration mConfig;
	private final ConnectionConfiguration mXMPPConfig;
	private final XMPPConnection mXMPPConnection;

	private XMPPServiceCallback mServiceCallBack;
	private Roster mRoster;
	private RosterListener mRosterListener;
	private PacketListener mPacketListener;

	private final ContentResolver mContentResolver;

	private PacketListener mSendFailureListener;
	private PacketListener mPongListener;
	private String mPingID;
	private long mPingTimestamp;

	private PendingIntent mPingAlarmPendIntent;
	private PendingIntent mPongTimeoutAlarmPendIntent;
	private static final String PING_ALARM = "org.yaxim.androidclient.PING_ALARM";
	private static final String PONG_TIMEOUT_ALARM = "org.yaxim.androidclient.PONG_TIMEOUT_ALARM";
	private Intent mPingAlarmIntent = new Intent(PING_ALARM);
	private Intent mPongTimeoutAlarmIntent = new Intent(PONG_TIMEOUT_ALARM);
	private Service mService;

	private PongTimeoutAlarmReceiver mPongTimeoutAlarmReceiver = new PongTimeoutAlarmReceiver();
	private BroadcastReceiver mPingAlarmReceiver = new PingAlarmReceiver();
	
	private Map<String, MultiUserChat> multiUserChats;


	public SmackableImp(YaximConfiguration config,
			ContentResolver contentResolver,
			Service service) {
		this.mConfig = config;
		// allow custom server / custom port to override SRV record
		if (mConfig.customServer.length() > 0 || mConfig.port != PreferenceConstants.DEFAULT_PORT_INT)
			this.mXMPPConfig = new ConnectionConfiguration(mConfig.customServer,
					mConfig.port, mConfig.server);
		else
			this.mXMPPConfig = new ConnectionConfiguration(mConfig.server); // use SRV
		this.mXMPPConfig.setReconnectionAllowed(false);
		this.mXMPPConfig.setSendPresence(false);
		this.mXMPPConfig.setCompressionEnabled(false); // disable for now
		this.mXMPPConfig.setDebuggerEnabled(mConfig.smackdebug);
		if (config.require_ssl)
			this.mXMPPConfig.setSecurityMode(ConnectionConfiguration.SecurityMode.required);

		// register MemorizingTrustManager for HTTPS
		try {
			SSLContext sc = SSLContext.getInstance("TLS");
			sc.init(null, new X509TrustManager[] { YaximApplication.getApp(service).mMTM },
					new java.security.SecureRandom());
			this.mXMPPConfig.setCustomSSLContext(sc);
		} catch (java.security.GeneralSecurityException e) {
			debugLog("initialize MemorizingTrustManager: " + e);
		}

		this.mXMPPConnection = new XMPPConnection(mXMPPConfig);
		this.mContentResolver = contentResolver;
		this.mService = service;
		
		this.multiUserChats = new HashMap<String, MultiUserChat>();
	}

	public boolean doConnect(boolean create_account) throws YaximXMPPException {
		tryToConnect(create_account);
		// actually, authenticated must be true now, or an exception must have
		// been thrown.
		if (isAuthenticated()) {
			registerMessageListener();
			registerMessageSendFailureListener();
			registerPongListener();
			sendOfflineMessages(); // TODO: before or after the mServiceCallBack==null block?
			syncDbRooms();
			if (mServiceCallBack == null) {
				// sometimes we get disconnected while not yet quite connected.
				// bail out if this is the case
				debugLog("doConnect: mServiceCallBack is null, aborting connection...");
				mXMPPConnection.disconnect();
				return false;
			}
			// we need to "ping" the service to let it know we are actually
			// connected, even when no roster entries will come in
			mServiceCallBack.rosterChanged();
		}
		return isAuthenticated();
	}

	private void initServiceDiscovery() {
		// register connection features
		ServiceDiscoveryManager sdm = ServiceDiscoveryManager.getInstanceFor(mXMPPConnection);
		if (sdm == null)
			sdm = new ServiceDiscoveryManager(mXMPPConnection);

		sdm.addFeature("http://jabber.org/protocol/disco#info");

		sdm.addFeature(Carbon.NAMESPACE);
		sdm.addFeature("urn:xmpp:ping");

		// reference PingManager, set ping flood protection to 10s
		PingManager.getInstanceFor(mXMPPConnection).setPingMinimumInterval(10*1000);
		// reference PingManager, set ping flood protection to 10s
		PingManager.getInstanceFor(mXMPPConnection).setPingMinimumInterval(10*1000);
		// reference DeliveryReceiptManager, add listener

		DeliveryReceiptManager dm = DeliveryReceiptManager.getInstanceFor(mXMPPConnection);
		dm.enableAutoReceipts();
		dm.registerReceiptReceivedListener(new DeliveryReceiptManager.ReceiptReceivedListener() {
			public void onReceiptReceived(String fromJid, String toJid, String receiptId) {
				Log.d(TAG, "got delivery receipt for " + receiptId);
				changeMessageDeliveryStatus(receiptId, ChatConstants.DS_ACKED);
			}});
	}

	public void addRosterItem(String user, String alias, String group)
			throws YaximXMPPException {
		tryToAddRosterEntry(user, alias, group);
	}

	public void removeRosterItem(String user) throws YaximXMPPException {
		debugLog("removeRosterItem(" + user + ")");

		tryToRemoveRosterEntry(user);
		mServiceCallBack.rosterChanged();
	}

	public void renameRosterItem(String user, String newName)
			throws YaximXMPPException {
		mRoster = mXMPPConnection.getRoster();
		RosterEntry rosterEntry = mRoster.getEntry(user);

		if (!(newName.length() > 0) || (rosterEntry == null)) {
			throw new YaximXMPPException("JabberID to rename is invalid!");
		}
		rosterEntry.setName(newName);
	}

	public void addRosterGroup(String group) {
		mRoster = mXMPPConnection.getRoster();
		mRoster.createGroup(group);
	}

	public void renameRosterGroup(String group, String newGroup) {
		mRoster = mXMPPConnection.getRoster();
		RosterGroup groupToRename = mRoster.getGroup(group);
		groupToRename.setName(newGroup);
	}

	public void moveRosterItemToGroup(String user, String group)
			throws YaximXMPPException {
		tryToMoveRosterEntryToGroup(user, group);
	}

	public void requestAuthorizationForRosterItem(String user) {
		Presence response = new Presence(Presence.Type.subscribe);
		response.setTo(user);
		mXMPPConnection.sendPacket(response);
	}

	private void tryToConnect(boolean create_account) throws YaximXMPPException {
		try {
			if (mXMPPConnection.isConnected()) {
				try {
					mXMPPConnection.disconnect();
				} catch (Exception e) {
					debugLog("conn.disconnect() failed: " + e);
				}
			}
			SmackConfiguration.setPacketReplyTimeout(PACKET_TIMEOUT);
			SmackConfiguration.setKeepAliveInterval(-1);
			SmackConfiguration.setDefaultPingInterval(0);
			registerRosterListener();
			mXMPPConnection.connect();
			if (!mXMPPConnection.isConnected()) {
				throw new YaximXMPPException("SMACK connect failed without exception!");
			}
			mXMPPConnection.addConnectionListener(new ConnectionListener() {
				public void connectionClosedOnError(Exception e) {
					mServiceCallBack.disconnectOnError();
				}
				public void connectionClosed() { }
				public void reconnectingIn(int seconds) { }
				public void reconnectionFailed(Exception e) { }
				public void reconnectionSuccessful() { }
			});
			initServiceDiscovery();
			// SMACK auto-logins if we were authenticated before
			if (!mXMPPConnection.isAuthenticated()) {
				if (create_account) {
					Log.d(TAG, "creating new server account...");
					AccountManager am = new AccountManager(mXMPPConnection);
					am.createAccount(mConfig.userName, mConfig.password);
				}
				mXMPPConnection.login(mConfig.userName, mConfig.password,
						mConfig.ressource);
			}
			setStatusFromConfig();

		} catch (XMPPException e) {
			throw new YaximXMPPException(e.getLocalizedMessage(), e.getWrappedThrowable());
		} catch (Exception e) {
			// actually we just care for IllegalState or NullPointer or XMPPEx.
			Log.e(TAG, "tryToConnect(): " + Log.getStackTraceString(e));
			throw new YaximXMPPException(e.getLocalizedMessage(), e.getCause());
		}
	}

	private void tryToMoveRosterEntryToGroup(String userName, String groupName)
			throws YaximXMPPException {

		mRoster = mXMPPConnection.getRoster();
		RosterGroup rosterGroup = getRosterGroup(groupName);
		RosterEntry rosterEntry = mRoster.getEntry(userName);

		removeRosterEntryFromGroups(rosterEntry);

		if (groupName.length() == 0)
			return;
		else {
			try {
				rosterGroup.addEntry(rosterEntry);
			} catch (XMPPException e) {
				throw new YaximXMPPException(e.getLocalizedMessage());
			}
		}
	}

	private RosterGroup getRosterGroup(String groupName) {
		RosterGroup rosterGroup = mRoster.getGroup(groupName);

		// create group if unknown
		if ((groupName.length() > 0) && rosterGroup == null) {
			rosterGroup = mRoster.createGroup(groupName);
		}
		return rosterGroup;

	}

	private void removeRosterEntryFromGroups(RosterEntry rosterEntry)
			throws YaximXMPPException {
		Collection<RosterGroup> oldGroups = rosterEntry.getGroups();

		for (RosterGroup group : oldGroups) {
			tryToRemoveUserFromGroup(group, rosterEntry);
		}
	}

	private void tryToRemoveUserFromGroup(RosterGroup group,
			RosterEntry rosterEntry) throws YaximXMPPException {
		try {
			group.removeEntry(rosterEntry);
		} catch (XMPPException e) {
			throw new YaximXMPPException(e.getLocalizedMessage());
		}
	}

	private void tryToRemoveRosterEntry(String user) throws YaximXMPPException {
		mRoster = mXMPPConnection.getRoster();
		try {
			RosterEntry rosterEntry = mRoster.getEntry(user);

			if (rosterEntry != null) {
				mRoster.removeEntry(rosterEntry);
			}
		} catch (XMPPException e) {
			throw new YaximXMPPException(e.getLocalizedMessage());
		}
	}

	private void tryToAddRosterEntry(String user, String alias, String group)
			throws YaximXMPPException {
		mRoster = mXMPPConnection.getRoster();
		try {
			mRoster.createEntry(user, alias, new String[] { group });
		} catch (XMPPException e) {
			throw new YaximXMPPException(e.getLocalizedMessage());
		}
	}

	private void removeOldRosterEntries() {
		Log.d(TAG, "removeOldRosterEntries()");
		mRoster = mXMPPConnection.getRoster();
		Collection<RosterEntry> rosterEntries = mRoster.getEntries();
		StringBuilder exclusion = new StringBuilder(RosterConstants.JID + " NOT IN (");
		boolean first = true;
		
		for (RosterEntry rosterEntry : rosterEntries) {
			updateRosterEntryInDB(rosterEntry);
			if (first)
				first = false;
			else
				exclusion.append(",");
			exclusion.append("'").append(rosterEntry.getUser()).append("'");
		}
		
		exclusion.append(") AND GROUP NOT 'MUCs';");
		int count = mContentResolver.delete(RosterProvider.CONTENT_URI, exclusion.toString(), null);
		Log.d(TAG, "deleted " + count + " old roster entries");
	}


	public void setStatusFromConfig() {
		if (mConfig.messageCarbons)
			CarbonManager.getInstanceFor(mXMPPConnection).sendCarbonsEnabled(true);

		Presence presence = new Presence(Presence.Type.available);
		Mode mode = Mode.valueOf(mConfig.statusMode);
		presence.setMode(mode);
		presence.setStatus(mConfig.statusMessage);
		presence.setPriority(mConfig.priority);
		mXMPPConnection.sendPacket(presence);
	}

	public void sendOfflineMessages() {
		Cursor cursor = mContentResolver.query(ChatProvider.CONTENT_URI,
				SEND_OFFLINE_PROJECTION, SEND_OFFLINE_SELECTION,
				null, null);
		final int      _ID_COL = cursor.getColumnIndexOrThrow(ChatConstants._ID);
		final int      JID_COL = cursor.getColumnIndexOrThrow(ChatConstants.JID);
		final int      MSG_COL = cursor.getColumnIndexOrThrow(ChatConstants.MESSAGE);
		final int       TS_COL = cursor.getColumnIndexOrThrow(ChatConstants.DATE);
		final int PACKETID_COL = cursor.getColumnIndexOrThrow(ChatConstants.PACKET_ID);
		ContentValues mark_sent = new ContentValues();
		mark_sent.put(ChatConstants.DELIVERY_STATUS, ChatConstants.DS_SENT_OR_READ);
		while (cursor.moveToNext()) {
			int _id = cursor.getInt(_ID_COL);
			String toJID = cursor.getString(JID_COL);
			String message = cursor.getString(MSG_COL);
			String packetID = cursor.getString(PACKETID_COL);
			long ts = cursor.getLong(TS_COL);
			Log.d(TAG, "sendOfflineMessages: " + toJID + " > " + message);
			final Message newMessage = new Message(toJID, Message.Type.chat);
			newMessage.setBody(message);
			DelayInformation delay = new DelayInformation(new Date(ts));
			newMessage.addExtension(delay);
			newMessage.addExtension(new DelayInfo(delay));
			newMessage.addExtension(new DeliveryReceiptRequest());
			if ((packetID != null) && (packetID.length() > 0)) {
				newMessage.setPacketID(packetID);
			} else {
				packetID = newMessage.getPacketID();
				mark_sent.put(ChatConstants.PACKET_ID, packetID);
			}
			Uri rowuri = Uri.parse("content://" + ChatProvider.AUTHORITY
				+ "/" + ChatProvider.TABLE_NAME + "/" + _id);
			mContentResolver.update(rowuri, mark_sent,
						null, null);
			mXMPPConnection.sendPacket(newMessage);		// must be after marking delivered, otherwise it may override the SendFailListener
		}
		cursor.close();
	}

	public static void sendOfflineMessage(ContentResolver cr, String toJID, String message) {
		ContentValues values = new ContentValues();
		values.put(ChatConstants.DIRECTION, ChatConstants.OUTGOING);
		values.put(ChatConstants.JID, toJID);
		values.put(ChatConstants.MESSAGE, message);
		values.put(ChatConstants.DELIVERY_STATUS, ChatConstants.DS_NEW);
		values.put(ChatConstants.DATE, System.currentTimeMillis());

		cr.insert(ChatProvider.CONTENT_URI, values);
	}

	public void sendReceipt(String toJID, String id) {
		Log.d(TAG, "sending XEP-0184 ack to " + toJID + " id=" + id);
		final Message ack = new Message(toJID, Message.Type.normal);
		ack.addExtension(new DeliveryReceipt(id));
		mXMPPConnection.sendPacket(ack);
	}

	public void sendMessage(String toJID, String message) {
		final Message newMessage = new Message(toJID, Message.Type.chat);
		newMessage.setBody(message);
		newMessage.addExtension(new DeliveryReceiptRequest());
		if (isAuthenticated()) {

			if(new ArrayList<String>(Arrays.asList(getJoinedRooms())).contains(toJID)) {
				sendMucMessage(toJID, message);
			} else {
				addChatMessageToDB(ChatConstants.OUTGOING, toJID, message, ChatConstants.DS_SENT_OR_READ,
						System.currentTimeMillis(), newMessage.getPacketID());
				mXMPPConnection.sendPacket(newMessage);
			}
		} else {
			// send offline -> store to DB
			addChatMessageToDB(ChatConstants.OUTGOING, toJID, message, ChatConstants.DS_NEW,
					System.currentTimeMillis(), newMessage.getPacketID());
		}
	}

	public boolean isAuthenticated() {
		if (mXMPPConnection != null) {
			return (mXMPPConnection.isConnected() && mXMPPConnection
					.isAuthenticated());
		}
		return false;
	}

	public void registerCallback(XMPPServiceCallback callBack) {
		this.mServiceCallBack = callBack;
	}

	public void unRegisterCallback() {
		debugLog("unRegisterCallback()");
		// remove callbacks _before_ tossing old connection
		try {
			mXMPPConnection.getRoster().removeRosterListener(mRosterListener);
			mXMPPConnection.removePacketListener(mPacketListener);
			mXMPPConnection.removePacketSendFailureListener(mSendFailureListener);
			mXMPPConnection.removePacketListener(mPongListener);
			((AlarmManager)mService.getSystemService(Context.ALARM_SERVICE)).cancel(mPingAlarmPendIntent);
			((AlarmManager)mService.getSystemService(Context.ALARM_SERVICE)).cancel(mPongTimeoutAlarmPendIntent);
			mService.unregisterReceiver(mPingAlarmReceiver);
			mService.unregisterReceiver(mPongTimeoutAlarmReceiver);
		} catch (Exception e) {
			// ignore it!
		}
		if (mXMPPConnection.isConnected()) {
			// work around SMACK's #%&%# blocking disconnect()
			new Thread() {
				public void run() {
					debugLog("shutDown thread started");
					mXMPPConnection.disconnect();
					debugLog("shutDown thread finished");
				}
			}.start();
		}
		setStatusOffline();
		this.mServiceCallBack = null;
	}
	
	public String getNameForJID(String jid) {
		if (null != this.mRoster.getEntry(jid) && null != this.mRoster.getEntry(jid).getName() && this.mRoster.getEntry(jid).getName().length() > 0) {
			return this.mRoster.getEntry(jid).getName();
		} else {
			return jid;
		}			
	}

	private void setStatusOffline() {
		ContentValues values = new ContentValues();
		values.put(RosterConstants.STATUS_MODE, StatusMode.offline.ordinal());
		mContentResolver.update(RosterProvider.CONTENT_URI, values, null, null);
	}

	private void registerRosterListener() {
		// flush roster on connecting.
		mRoster = mXMPPConnection.getRoster();

		mRosterListener = new RosterListener() {
			private boolean first_roster = true;

			public void entriesAdded(Collection<String> entries) {
				debugLog("entriesAdded(" + entries + ")");

				ContentValues[] cvs = new ContentValues[entries.size()];
				int i = 0;
				for (String entry : entries) {
					RosterEntry rosterEntry = mRoster.getEntry(entry);
					cvs[i++] = getContentValuesForRosterEntry(rosterEntry);
				}
				mContentResolver.bulkInsert(RosterProvider.CONTENT_URI, cvs);
				// when getting the roster in the beginning, remove remains of old one
				if (first_roster) {
					removeOldRosterEntries();
					first_roster = false;
					mServiceCallBack.rosterChanged();
				}
				debugLog("entriesAdded() done");
			}

			public void entriesDeleted(Collection<String> entries) {
				debugLog("entriesDeleted(" + entries + ")");

				for (String entry : entries) {
					deleteRosterEntryFromDB(entry);
				}
				mServiceCallBack.rosterChanged();
			}

			public void entriesUpdated(Collection<String> entries) {
				debugLog("entriesUpdated(" + entries + ")");

				for (String entry : entries) {
					RosterEntry rosterEntry = mRoster.getEntry(entry);
					updateRosterEntryInDB(rosterEntry);
				}
				mServiceCallBack.rosterChanged();
			}

			public void presenceChanged(Presence presence) {
				debugLog("presenceChanged(" + presence.getFrom() + "): " + presence);

				String jabberID = getJabberID(presence.getFrom())[0];
				RosterEntry rosterEntry = mRoster.getEntry(jabberID);
				updateRosterEntryInDB(rosterEntry);
				mServiceCallBack.rosterChanged();
			}
		};
		mRoster.addRosterListener(mRosterListener);
	}

	private String[] getJabberID(String from) {
		if(from.contains("/")) {
			String[] res = from.split("/");
			return new String[] { res[0], res[1] };
		} else {
			return new String[] {from, ""};
		}
	}

	public void changeMessageDeliveryStatus(String packetID, int new_status) {
		ContentValues cv = new ContentValues();
		cv.put(ChatConstants.DELIVERY_STATUS, new_status);
		Uri rowuri = Uri.parse("content://" + ChatProvider.AUTHORITY + "/"
				+ ChatProvider.TABLE_NAME);
		mContentResolver.update(rowuri, cv,
				ChatConstants.PACKET_ID + " = ? AND " +
				ChatConstants.DIRECTION + " = " + ChatConstants.OUTGOING,
				new String[] { packetID });
	}

	public void sendServerPing() {
		if (mPingID != null) {
			debugLog("Ping: requested, but still waiting for " + mPingID);
			return; // a ping is still on its way
		}
		Ping ping = new Ping();
		ping.setType(Type.GET);
		ping.setTo(mConfig.server);
		mPingID = ping.getPacketID();
		mPingTimestamp = System.currentTimeMillis();
		debugLog("Ping: sending ping " + mPingID);
		mXMPPConnection.sendPacket(ping);

		// register ping timeout handler: PACKET_TIMEOUT(30s) + 3s
		((AlarmManager)mService.getSystemService(Context.ALARM_SERVICE)).set(AlarmManager.RTC_WAKEUP,
			System.currentTimeMillis() + PACKET_TIMEOUT + 3000, mPongTimeoutAlarmPendIntent);
	}

	/**
	 * BroadcastReceiver to trigger reconnect on pong timeout.
	 */
	private class PongTimeoutAlarmReceiver extends BroadcastReceiver {
		public void onReceive(Context ctx, Intent i) {
			debugLog("Ping: timeout for " + mPingID);
			mServiceCallBack.disconnectOnError();
			unRegisterCallback();
		}
	}

	/**
	 * BroadcastReceiver to trigger sending pings to the server
	 */
	private class PingAlarmReceiver extends BroadcastReceiver {
		public void onReceive(Context ctx, Intent i) {
			if (mXMPPConnection.isAuthenticated()) {
				sendServerPing();
			} else
				debugLog("Ping: alarm received, but not connected to server.");
		}
	}

	/**
	 * Registers a smack packet listener for IQ packets, intended to recognize "pongs" with
	 * a packet id matching the last "ping" sent to the server.
	 *
	 * Also sets up the AlarmManager Timer plus necessary intents.
	 */
	private void registerPongListener() {
		// reset ping expectation on new connection
		mPingID = null;

		if (mPongListener != null)
			mXMPPConnection.removePacketListener(mPongListener);

		mPongListener = new PacketListener() {

			@Override
			public void processPacket(Packet packet) {
				if (packet == null) return;

				if (packet.getPacketID().equals(mPingID)) {
					Log.i(TAG, String.format("Ping: server latency %1.3fs",
								(System.currentTimeMillis() - mPingTimestamp)/1000.));
					mPingID = null;
					((AlarmManager)mService.getSystemService(Context.ALARM_SERVICE)).cancel(mPongTimeoutAlarmPendIntent);
				}
			}

		};

		mXMPPConnection.addPacketListener(mPongListener, new PacketTypeFilter(IQ.class));
		mPingAlarmPendIntent = PendingIntent.getBroadcast(mService.getApplicationContext(), 0, mPingAlarmIntent,
					PendingIntent.FLAG_UPDATE_CURRENT);
		mPongTimeoutAlarmPendIntent = PendingIntent.getBroadcast(mService.getApplicationContext(), 0, mPongTimeoutAlarmIntent,
					PendingIntent.FLAG_UPDATE_CURRENT);
		mService.registerReceiver(mPingAlarmReceiver, new IntentFilter(PING_ALARM));
		mService.registerReceiver(mPongTimeoutAlarmReceiver, new IntentFilter(PONG_TIMEOUT_ALARM));
		((AlarmManager)mService.getSystemService(Context.ALARM_SERVICE)).setInexactRepeating(AlarmManager.RTC_WAKEUP, 
				System.currentTimeMillis() + AlarmManager.INTERVAL_FIFTEEN_MINUTES, AlarmManager.INTERVAL_FIFTEEN_MINUTES, mPingAlarmPendIntent);
	}

	private void registerMessageSendFailureListener() {
		// do not register multiple packet listeners
		if (mSendFailureListener != null)
			mXMPPConnection.removePacketSendFailureListener(mSendFailureListener);

		PacketTypeFilter filter = new PacketTypeFilter(Message.class);

		mSendFailureListener = new PacketListener() {
			public void processPacket(Packet packet) {
				try {
				if (packet instanceof Message) {
					Message msg = (Message) packet;
					String chatMessage = msg.getBody();

					Log.d("SmackableImp", "message " + chatMessage + " could not be sent (ID:" + (msg.getPacketID() == null ? "null" : msg.getPacketID()) + ")");
					changeMessageDeliveryStatus(msg.getPacketID(), ChatConstants.DS_NEW);
				}
				} catch (Exception e) {
					// SMACK silently discards exceptions dropped from processPacket :(
					Log.e(TAG, "failed to process packet:");
					e.printStackTrace();
				}
			}
		};

		mXMPPConnection.addPacketSendFailureListener(mSendFailureListener, filter);
	}

	private void registerMessageListener() {
		// do not register multiple packet listeners
		if (mPacketListener != null)
			mXMPPConnection.removePacketListener(mPacketListener);

		PacketTypeFilter filter = new PacketTypeFilter(Message.class);

		mPacketListener = new PacketListener() {
			public void processPacket(Packet packet) {
				try {
				if (packet instanceof Message) {
					Message msg = (Message) packet;
					String chatMessage = msg.getBody();

					if (msg.getExtension("request", DeliveryReceipt.NAMESPACE) != null) {
						// got XEP-0184 request, send receipt
						sendReceipt(msg.getFrom(), msg.getPacketID());
					}

					// try to extract a carbon
					Carbon cc = CarbonManager.getCarbon(msg);
					if (cc != null && cc.getDirection() == Carbon.Direction.received) {
						Log.d(TAG, "carbon: " + cc.toXML());
						msg = (Message)cc.getForwarded().getForwardedPacket();
						chatMessage = msg.getBody();
						// fall through
					}  else if (cc != null && cc.getDirection() == Carbon.Direction.sent) {
						Log.d(TAG, "carbon: " + cc.toXML());
						msg = (Message)cc.getForwarded().getForwardedPacket();
						chatMessage = msg.getBody();
						if (chatMessage == null) return;
						String[] fromJID = getJabberID(msg.getTo());

						addChatMessageToDB(ChatConstants.OUTGOING, fromJID, chatMessage, ChatConstants.DS_SENT_OR_READ, System.currentTimeMillis(), msg.getPacketID());
						// always return after adding
						return;
					}
					
					// check for jabber MUC invitation
					if(msg.getExtension("jabber:x:conference") != null) {
						Log.d(TAG, "handling MUC invitation and aborting futher packet processing...");
						handleMucInvitation(msg);
						return;
					}
					
					if (chatMessage == null) {
						return;
					}

					if (msg.getType() == Message.Type.error) {
						chatMessage = "<Error> " + chatMessage;
					}

					long ts;
					DelayInfo timestamp = (DelayInfo)msg.getExtension("delay", "urn:xmpp:delay");
					if (timestamp == null)
						timestamp = (DelayInfo)msg.getExtension("x", "jabber:x:delay");
					if (timestamp != null)
						ts = timestamp.getStamp().getTime();
					else
						ts = System.currentTimeMillis();

					
					String[] fromJID = getJabberID(msg.getFrom());
					
					Log.d(TAG, 
							String.format("attempting to add message '''%s''' from %s to db, msgtype==groupchat?: %b, checkaddmucmessage is: %b", chatMessage, fromJID[0], msg.getType()==Message.Type.groupchat, checkAddMucMessage(msg, ts, fromJID))
							);
					if(msg.getType() != Message.Type.groupchat
						|| 
						(msg.getType()==Message.Type.groupchat && checkAddMucMessage(msg, ts, fromJID))
						) {
							Log.d(TAG, "actually adding msg...");
							addChatMessageToDB(ChatConstants.INCOMING, fromJID, chatMessage, 
									   ChatConstants.DS_NEW, ts, msg.getPacketID());
							mServiceCallBack.newMessage(fromJID, chatMessage, msg.getType());
						}
					}
				} catch (Exception e) {
					// SMACK silently discards exceptions dropped from processPacket :(
					Log.e(TAG, "failed to process packet:");
					e.printStackTrace();
				}
			}
		};

		mXMPPConnection.addPacketListener(mPacketListener, filter);
	}


	private boolean checkAddMucMessage(Message msg, long ts, String[] fromJid ) {
		final String[] projection = new String[] {
				ChatConstants._ID, ChatConstants.DATE,
				ChatConstants.JID, ChatConstants.MESSAGE,
				ChatConstants.PACKET_ID
		};
				
		//final String content_match= ChatConstants.JID+"='"+fromJid[0]+"' AND "+ChatConstants.MESSAGE+"='"+msg.getBody()+"'"
		//		+" AND "+ChatConstants.DATE+"='"+ts+"'";
		//final String packet_match = ChatConstants.PACKET_ID+"='"+msg.getPacketID()+"'";
		//final String selection = "("+content_match+") OR ("+packet_match+")";
		final String selection = ChatConstants.JID+"='"+fromJid[0]+"'";
		final String order = ChatConstants.DATE+" DESC LIMIT 5";
		try {
			Cursor cursor = mContentResolver.query(ChatProvider.CONTENT_URI, projection, selection, null, order);
			cursor.moveToFirst();
			while(!cursor.isLast()) {
				String pid = cursor.getString( cursor.getColumnIndexOrThrow(ChatConstants.PACKET_ID) );
				Log.d(TAG, "processing cursor row, got pid: "+pid+" comparing with "+msg.getPacketID());
				if( pid.equals(msg.getPacketID()) ) {
					return false;
				}
				cursor.moveToNext();
			}
		} catch (Exception e) {} // just return true...

		return true;	
	}

	private void addChatMessageToDB(int direction, String[] JID,
			String message, int delivery_status, long ts, 
			String packetID) {
		ContentValues values = new ContentValues();
		
		values.put(ChatConstants.DIRECTION, direction);
		values.put(ChatConstants.JID, JID[0]);
		values.put(ChatConstants.RESOURCE, JID[1]);
		values.put(ChatConstants.MESSAGE, message);
		values.put(ChatConstants.DELIVERY_STATUS, delivery_status);
		values.put(ChatConstants.DATE, ts);
		values.put(ChatConstants.PACKET_ID, packetID);

		mContentResolver.insert(ChatProvider.CONTENT_URI, values);
	}

	private void addChatMessageToDB(int direction, String JID,
			String message, int delivery_status, long ts, String packetID) {
		String[] tJID = {JID, ""};
		addChatMessageToDB(direction, tJID, message, delivery_status, ts, packetID);
	}

	private ContentValues getContentValuesForRosterEntry(final RosterEntry entry) {
		final ContentValues values = new ContentValues();

		values.put(RosterConstants.JID, entry.getUser());
		values.put(RosterConstants.ALIAS, getName(entry));

		Presence presence = mRoster.getPresence(entry.getUser());
		values.put(RosterConstants.STATUS_MODE, getStatusInt(presence));
		values.put(RosterConstants.STATUS_MESSAGE, presence.getStatus());
		values.put(RosterConstants.GROUP, getGroup(entry.getGroups()));

		return values;
	}

	private void addRosterEntryToDB(final RosterEntry entry) {
		ContentValues values = getContentValuesForRosterEntry(entry);
		Uri uri = mContentResolver.insert(RosterProvider.CONTENT_URI, values);
		debugLog("addRosterEntryToDB: Inserted " + uri);
	}

	private void deleteRosterEntryFromDB(final String jabberID) {
		int count = mContentResolver.delete(RosterProvider.CONTENT_URI,
				RosterConstants.JID + " = ?", new String[] { jabberID });
		debugLog("deleteRosterEntryFromDB: Deleted " + count + " entries");
	}

	private void updateRosterEntryInDB(final RosterEntry entry) {
		final ContentValues values = getContentValuesForRosterEntry(entry);

		if (mContentResolver.update(RosterProvider.CONTENT_URI, values,
				RosterConstants.JID + " = ?", new String[] { entry.getUser() }) == 0)
			addRosterEntryToDB(entry);
	}

	private String getGroup(Collection<RosterGroup> groups) {
		for (RosterGroup group : groups) {
			return group.getName();
		}
		return "";
	}

	private String getName(RosterEntry rosterEntry) {
		String name = rosterEntry.getName();
		if (name != null && name.length() > 0) {
			return name;
		}
		name = StringUtils.parseName(rosterEntry.getUser());
		if (name.length() > 0) {
			return name;
		}
		return rosterEntry.getUser();
	}

	private StatusMode getStatus(Presence presence) {
		if (presence.getType() == Presence.Type.available) {
			if (presence.getMode() != null) {
				return StatusMode.valueOf(presence.getMode().name());
			}
			return StatusMode.available;
		}
		return StatusMode.offline;
	}

	private int getStatusInt(final Presence presence) {
		return getStatus(presence).ordinal();
	}

	private void debugLog(String data) {
		if (LogConstants.LOG_DEBUG) {
			Log.d(TAG, data);
		}
	}

	public void syncDbRooms() {
		ArrayList<String> joinedRooms = new ArrayList<String>(Arrays.asList(getJoinedRooms()));
		Cursor cursor = mContentResolver.query(RosterProvider.MUCS_URI, 
				new String[] {RosterProvider.RosterConstants._ID,
					RosterProvider.RosterConstants.JID, 
					RosterProvider.RosterConstants.PASSWORD, 
					RosterProvider.RosterConstants.NICKNAME}, 
				null, null, null);
		final int ID = cursor.getColumnIndexOrThrow(RosterProvider.RosterConstants._ID);
		final int JID_ID = cursor.getColumnIndexOrThrow(RosterProvider.RosterConstants.JID);
		final int PASSWORD_ID = cursor.getColumnIndexOrThrow(RosterProvider.RosterConstants.PASSWORD);
		final int NICKNAME_ID = cursor.getColumnIndexOrThrow(RosterProvider.RosterConstants.NICKNAME);
		
		ArrayList<String> dbRooms = new ArrayList<String>();
		while(cursor.moveToNext()) {
			int id = cursor.getInt(ID);
			String jid = cursor.getString(JID_ID);
			String password = cursor.getString(PASSWORD_ID);
			String nickname = cursor.getString(NICKNAME_ID);
			dbRooms.add(jid);
			//debugLog("Found MUC Room: "+jid+" with nick "+nickname+" and pw "+password);
			if(!joinedRooms.contains(jid)) {
				debugLog("room isn't joined yet, i wanna join...");
				joinRoom(jid, nickname, password); // TODO: make historyLen configurable
			}
			//debugLog("found data in contentprovider: "+jid+" "+password+" "+nickname);
		}
		
		for(String room : joinedRooms) {
			if(!dbRooms.contains(room)) {
				quitRoom(room);
			}
		}
	}
	
	protected void handleMucInvitation(Message msg) {
		mServiceCallBack.mucInvitationReceived(
				msg.getFrom(),
				msg.getBody()
				);
	}
	
	public boolean addRoom(String jid, String password, String nickname) {
		ContentValues cv = new ContentValues();
		cv.put(RosterProvider.RosterConstants.JID, jid);
		cv.put(RosterProvider.RosterConstants.NICKNAME, nickname);
		cv.put(RosterProvider.RosterConstants.PASSWORD, password);
		Uri ret = mContentResolver.insert(RosterProvider.MUCS_URI, cv);
		syncDbRooms();
		
		return (ret != null);
	}
	
	public boolean removeRoom(String jid) {
		int deleted = mContentResolver.delete(RosterProvider.MUCS_URI, 
				RosterProvider.RosterConstants.JID+" LIKE ?", 
				new String[] {jid});
		syncDbRooms();
		return (deleted > 0);
	}
	
	private boolean joinRoom(String room, String nickname, String password) {
		MultiUserChat muc = new MultiUserChat(mXMPPConnection, room);
		
		DiscussionHistory history = new DiscussionHistory();
		final String[] projection = new String[] {
				ChatConstants._ID, ChatConstants.DATE,
				ChatConstants.JID, ChatConstants.MESSAGE,
				ChatConstants.PACKET_ID
		};
		final String selection = String.format("%s = '%s'", projection[2], room);
		Cursor cursor = mContentResolver.query(ChatProvider.CONTENT_URI, projection, 
				selection, null, "date DESC LIMIT 1");
		if(cursor.getCount()>0) {
			cursor.moveToFirst();
			long lastDate = cursor.getLong( cursor.getColumnIndexOrThrow(projection[1]) );
			String msg =  cursor.getString( cursor.getColumnIndexOrThrow(projection[3]) );
			Log.d(TAG, String.format("joining room %s i found %d rows of last date %d with msg %s, setting since to %s", room, cursor.getCount(), lastDate, msg, (new Date(lastDate)).toString()) );
			history.setSince( new Date(lastDate) );
		} else Log.d(TAG, "found no old DB messages");
		
		
		try {
			muc.join(nickname, password, history, SmackConfiguration.getPacketReplyTimeout());
		} catch (Exception e) {
			Log.e(TAG, "Could not join MUC-room "+room);
			e.printStackTrace();
			if(nickname == null || nickname.equals("")) {
				joinRoom(room, "NoNick", password);
			}
			return false;
		}

		if(muc.isJoined()) {
			multiUserChats.put(room, muc);
			ContentValues cvR = new ContentValues();
			cvR.put(RosterProvider.RosterConstants.JID,room);
			cvR.put(RosterProvider.RosterConstants.ALIAS,room);
			cvR.put(RosterProvider.RosterConstants.STATUS_MESSAGE,"");
			cvR.put(RosterProvider.RosterConstants.STATUS_MODE,4);
			cvR.put(RosterProvider.RosterConstants.GROUP,"MUCs");
			Uri ret2 = mContentResolver.insert(RosterProvider.CONTENT_URI, cvR);
			return true;
		}
		
		return false;
	}

	private String[] getJoinedRooms() {
		if (multiUserChats.keySet().size() != 0) {
			return (String[]) multiUserChats.keySet().toArray(new String[]{});
		} else {
			return new String[] {};
		}
	}

	@Override
	public boolean createAndJoinRoom(String jid, String password, String nickname) { // TODO: ugly and not working!
		createRoom(jid, nickname, password);
		if(new ArrayList<String>(Arrays.asList(getJoinedRooms())).contains(jid)) {
			ContentValues cv = new ContentValues();
			cv.put(RosterProvider.RosterConstants.JID, jid);
			cv.put(RosterProvider.RosterConstants.NICKNAME, nickname);
			cv.put(RosterProvider.RosterConstants.PASSWORD, password);
			Uri ret = mContentResolver.insert(RosterProvider.MUCS_URI, cv);
		}
		return false;
	}
	
	private boolean createRoom(String room, String nickname, String password) { // TODO: ugly!
		// Create a MultiUserChat using a Connection for a room
		MultiUserChat muc = new MultiUserChat(mXMPPConnection, room);
		Form form = null; // TODO: maybe not good style?

		// Create the room
		try {
			muc.create(nickname);
		} catch (XMPPException e) {
			Log.e(TAG, "could not create MUC room "+room);
			e.printStackTrace();
			return false;
		}


		try {
			form = muc.getConfigurationForm();
		} catch (XMPPException e) {
			Log.e(TAG, "could not get configuration for MUC room "+room);
			e.printStackTrace();
			return false;
		}

		Form submitForm = form.createAnswerForm();
		for (Iterator fields = form.getFields(); fields.hasNext();) {
			FormField field = (FormField) fields.next();
			if (!FormField.TYPE_HIDDEN.equals(field.getType()) && field.getVariable() != null) {
				Log.d(TAG, "found MUC configuration form field: "+field.getLabel()); // TODO: until i know which fields to change
				submitForm.setDefaultAnswer(field.getVariable());
			}	
		}	

		//List owners = new ArrayList();
		//submitForm.setAnswer("",...); // TODO: adapt this to the fields found above 
		// Send the completed form (with default values) to the server to configure the room
		try {
			muc.sendConfigurationForm(submitForm);
		} catch (XMPPException e) {
			Log.e(TAG, "could not send MUC configuration for room "+room);
			e.printStackTrace();
			return false;
		}

		if(muc.isJoined()) {
			multiUserChats.put(room, muc);
			return true;
		}
		return false;
	}

	@Override
	public void sendMucMessage(String room, String message) {
		try {
			multiUserChats.get(room).sendMessage(message);
		} catch (XMPPException e) {
			Log.e(TAG, "error while sending message to muc room "+room);
			e.printStackTrace();
		}
	}

	private void quitRoom(String room) {
		MultiUserChat muc = multiUserChats.get(room); 
		muc.leave();
		multiUserChats.remove(room);
		mContentResolver.delete(RosterProvider.CONTENT_URI, "jid LIKE ?", new String[] {room});
	}

	@Override
	public String[] getRooms() {
		syncDbRooms();
		return getJoinedRooms();
	}

	@Override
	public boolean isRoom(String jid) {
		syncDbRooms();
		return new ArrayList<String>(Arrays.asList(getJoinedRooms())).contains(jid);
	}

	@Override
	public boolean inviteToRoom(String contactJid, String roomJid) {
		MultiUserChat muc = multiUserChats.get(roomJid);
		if(contactJid.contains("/")) {
			contactJid = contactJid.split("/")[0];
		}
		Log.d(TAG, "invitng contact: "+contactJid+" to room: "+muc);
		muc.invite(contactJid, "User "+contactJid+" has invited you to a chat!");
		return false;
	}

	@Override
	public String[] getUserList(String jid) {
		MultiUserChat muc = null;
		try {
			muc = multiUserChats.get(jid);
		} catch (Exception e) {
			return new String[] {};
		}
		Iterator<String> occIter = muc.getOccupants();
		ArrayList<String> tmpList = new ArrayList<String>();
		while(occIter.hasNext())
			tmpList.add(occIter.next().split("/")[1]);
		return (String[]) tmpList.toArray(new String[]{});
	}
}
