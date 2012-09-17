package org.buddycloud.channelserver.packetprocessor.iq.namespace.pubsub.get;

import java.io.StringReader;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import org.buddycloud.channelserver.pubsub.subscription.NodeSubscription;
import org.buddycloud.channelserver.pubsub.subscription.Subscriptions;
import org.apache.log4j.Logger;
import org.buddycloud.channelserver.channel.node.configuration.field.AccessModel;
import org.buddycloud.channelserver.db.DataStore;
import org.buddycloud.channelserver.db.jedis.NodeEntryImpl;
import org.buddycloud.channelserver.db.jedis.NodeSubscriptionImpl;
import org.buddycloud.channelserver.packetprocessor.iq.namespace.pubsub.JabberPubsub;
import org.buddycloud.channelserver.packetprocessor.iq.namespace.pubsub.PubSubElementProcessor;
import org.buddycloud.channelserver.packetprocessor.iq.namespace.pubsub.PubSubGet;
import org.buddycloud.channelserver.pubsub.accessmodel.AccessModels;
import org.buddycloud.channelserver.pubsub.affiliation.Affiliations;
import org.buddycloud.channelserver.pubsub.entry.NodeEntry;
import org.buddycloud.channelserver.utils.node.NodeAclRefuseReason;
import org.buddycloud.channelserver.utils.node.NodeViewAcl;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.dom.DOMElement;
import org.dom4j.io.SAXReader;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Packet;
import org.xmpp.packet.PacketError;
import org.xmpp.packet.PacketError.Condition;
import org.xmpp.packet.PacketError.Type;
import org.buddycloud.channelserver.db.jedis.NodeSubscriptionImpl;
import org.buddycloud.channelserver.db.DataStoreException;

public class ItemsGet implements PubSubElementProcessor {
	private static final Logger LOGGER = Logger.getLogger(ItemsGet.class);

	private static final int MAX_ITEMS_TO_RETURN = 50;

	private final BlockingQueue<Packet> outQueue;

	private DataStore dataStore;
	private String node;
	private String firstItem;
	private String lastItem;
	private SAXReader xmlReader;
	private Element entry;
	private IQ requestIq;
	private JID fetchersJid;
	private IQ reply;
	private Element resultSetManagement;
	private Element element;

	private NodeViewAcl nodeViewAcl;
	private HashMap<String, String> nodeDetails;

	public ItemsGet(BlockingQueue<Packet> outQueue, DataStore dataStore) {
		this.outQueue = outQueue;
		setDataStore(dataStore);
	}

	public void setDataStore(DataStore ds) {
		dataStore = ds;
	}

	public void setNodeViewAcl(NodeViewAcl acl) {
		nodeViewAcl = acl;
	}

	private NodeViewAcl getNodeViewAcl() {
		if (null == nodeViewAcl) {
			nodeViewAcl = new NodeViewAcl();
		}
		return nodeViewAcl;
	}

	@Override
	public void process(Element elm, JID actorJID, IQ reqIQ, Element rsm)
			throws Exception {
		node = elm.attributeValue("node");
		requestIq = reqIQ;
		reply = IQ.createResultIQ(reqIQ);
		element = elm;
		resultSetManagement = rsm;

		if ((node == null) || (true == node.equals(""))) {
			missingJidRequest();
			outQueue.put(reply);
			return;
		}

		fetchersJid = requestIq.getFrom();

		try {
			if (false == nodeExists()) {
				outQueue.put(reply);
				return;
			}
			// boolean isLocalNode = dataStore.isLocalNode(node);
			// boolean isLocalSubscriber = false;

			if (actorJID != null) {
				fetchersJid = actorJID;
			} else {
				// isLocalSubscriber =
				// dataStore.isLocalUser(fetchersJid.toBareJID());
			}
			/*
			 * if (!isLocalNode) { handleForeignNode(isLocalSubscriber); return;
			 * }
			 */
			if (false == userCanViewNode()) {
				outQueue.add(reply);
				return;
			}
			getItems();
		} catch (DataStoreException e) {
			setErrorCondition(PacketError.Type.wait,
					PacketError.Condition.internal_server_error);
		}
		outQueue.put(reply);
	}

	private boolean nodeExists() throws DataStoreException {

		if (true == dataStore.nodeExists(node)) {
			nodeDetails = dataStore.getNodeConf(node);
			return true;
		}
		setErrorCondition(PacketError.Type.cancel,
				PacketError.Condition.item_not_found);
		return false;
	}

	private void setErrorCondition(Type type, Condition condition) {
		reply.setType(IQ.Type.error);
		PacketError error = new PacketError(condition, type);
		reply.setError(error);
	}

	private void getItems() throws Exception {
		Element pubsub = new DOMElement(PubSubGet.ELEMENT_NAME,
				new org.dom4j.Namespace("", JabberPubsub.NAMESPACE_URI));

		int maxItemsToReturn = MAX_ITEMS_TO_RETURN;
		String afterItemId = null;

		String max_items = element.attributeValue("max_items");
		if (max_items != null) {
			maxItemsToReturn = Integer.parseInt(max_items);
		}

		// Requests
		// <set xmlns='http://jabber.org/protocol/rsm'>
		// <max>10</max>
		// </set>
		//
		// <set xmlns='http://jabber.org/protocol/rsm'>
		// <max>10</max>
		// <after>peterpan@neverland.lit</after>
		// </set>
		if (resultSetManagement != null) {
			Element max = resultSetManagement.element("max");
			if (max != null) {
				maxItemsToReturn = Integer.parseInt(max.getTextTrim());
			}
			Element after = resultSetManagement.element("after");
			if (after != null) {
				afterItemId = after.getTextTrim();
			}
		}

		Element items = pubsub.addElement("items");
		items.addAttribute("node", node);

		xmlReader = new SAXReader();
		entry = null;
		int totalEntriesCount = 0;

		if (node.substring(node.length() - 13).equals("subscriptions")) {
			totalEntriesCount = getSubscriptionItems(items, maxItemsToReturn,
					afterItemId);
		} else {
			totalEntriesCount = getNodeItems(items, maxItemsToReturn,
					afterItemId);
		}

		if ((resultSetManagement != null)
				|| (totalEntriesCount > maxItemsToReturn)) {
			/*
			 * TODO, add result set here as defined in 6.5.4 Returning Some
			 * Items <set xmlns='http://jabber.org/protocol/rsm'> <first
			 * index='0'>368866411b877c30064a5f62b917cffe</first>
			 * <last>4e30f35051b7b8b42abe083742187228</last> <count>19</count>
			 * </set>
			 */
			Element rsm = pubsub.addElement("set",
					"http://jabber.org/protocol/rsm");

			if (firstItem != null) {
				rsm.addElement("first").setText(firstItem);
				rsm.addElement("last").setText(lastItem);
			}
			rsm.addElement("count")
					.setText(Integer.toString(totalEntriesCount));
		}

		reply.setChildElement(pubsub);
	}

	private boolean userCanViewNode() throws DataStoreException {
		NodeSubscriptionImpl nodeSubscription = dataStore
				.getUserSubscriptionOfNode(fetchersJid.toBareJID(), node);
		String possibleExistingAffiliation = Affiliations.none.toString();
		String possibleExistingSubscription = Subscriptions.none.toString();
        if (null != nodeSubscription) {
			if (null != nodeSubscription.getAffiliation()) {
				possibleExistingAffiliation = nodeSubscription.getAffiliation();
			}
			if (null != nodeSubscription.getSubscription()) {
				possibleExistingSubscription = nodeSubscription.getSubscription();
			}
        }
		if (true == getNodeViewAcl().canViewNode(node,
				possibleExistingAffiliation, possibleExistingSubscription,
				getNodeAccessModel())) {
			return true;
		}
		NodeAclRefuseReason reason = getNodeViewAcl().getReason();
		createExtendedErrorReply(reason.getType(), reason.getCondition(),
				reason.getAdditionalErrorElement());
		return false;
	}

	private String getNodeAccessModel() {
		if (false == nodeDetails.containsKey(AccessModel.FIELD_NAME)) {
			return AccessModels.authorize.toString();
		}
		return nodeDetails.get(AccessModel.FIELD_NAME);
	}

	private void handleForeignNode(boolean isLocalSubscriber)
			throws InterruptedException {
		if (isLocalSubscriber) {

			// TODO, WORK HERE!

			// Start process to fetch items from nodes.
			// Subscribe sub = Subscribe.buildSubscribeStatemachine(node,
			// requestIq, dataStore);
			// outQueue.put(sub.nextStep());
			// return;
		}

		// Foreign client is trying to fetch items of a node that does not
		// exists.
		/*
		 * 6.1.3.12 Node Does Not Exist
		 * 
		 * <iq type='error' from='pubsub.shakespeare.lit'
		 * to='francisco@denmark.lit/barracks' id='sub1'> <error type='cancel'>
		 * <item-not-found xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/>
		 * </error> </iq>
		 */
		IQ reply = IQ.createResultIQ(requestIq);
		reply.setType(IQ.Type.error);
		PacketError pe = new PacketError(
				org.xmpp.packet.PacketError.Condition.item_not_found,
				org.xmpp.packet.PacketError.Type.cancel);
		reply.setError(pe);
		outQueue.put(reply);
		return;
	}

	private int getNodeItems(Element items, int maxItemsToReturn,
			String afterItemId) throws Exception {
		Iterator<? extends NodeEntry> cur = dataStore.getNodeEntries(node,
				maxItemsToReturn, afterItemId);

		if (null == cur) {
			return 0;
		}
		while (cur.hasNext()) {
			NodeEntryImpl nodeEntry = (NodeEntryImpl) cur.next();
			if (firstItem == null) {
				firstItem = nodeEntry.getMongoId();
			}
			try {
				entry = xmlReader.read(new StringReader(nodeEntry.getEntry()))
						.getRootElement();
				Element item = items.addElement("item");
				item.addAttribute("id", nodeEntry.getId());
				item.add(entry);
				lastItem = nodeEntry.getMongoId();
			} catch (DocumentException e) {
				LOGGER.error("Error parsing a node entry, ignoring (id: "
						+ nodeEntry.getMongoId() + ")");
			}

		}
		return dataStore.getNodeEntriesCount(node);
	}

	private int getSubscriptionItems(Element items, int maxItemsToReturn,
			String afterItemId) throws DataStoreException {
		Iterator<? extends NodeSubscription> subscribers = dataStore
				.getNodeSubscribers(node);
		int entries = 0;
		if (null == subscribers) {
			return entries;
		}
		Element jidItem;
		Element query;

		while (subscribers.hasNext()) {
			NodeSubscriptionImpl subscriber = (NodeSubscriptionImpl) subscribers
					.next();
			jidItem = items.addElement("item");
			jidItem.addAttribute("id", subscriber.getBareJID());
			query = jidItem.addElement("query");
			query.addNamespace("", JabberPubsub.NS_DISCO_ITEMS);

			if (firstItem == null) {
				firstItem = subscriber.getBareJID();
			}
			lastItem = subscriber.getBareJID();
			addSubscriptionItems(query, subscriber.getBareJID());
			entries++;
		}
		return entries;
	}

	private void addSubscriptionItems(Element query, String subscriber) {
		Iterator<? extends NodeSubscription> subscriptions = dataStore
				.findUserSubscriptionOfNodes(fetchersJid.toBareJID(),
						subscriber);
		Element item;
		
		if (null == subscriptions) {
			return;
		}
		while (subscriptions.hasNext()) {
			// TODO Query in a loop, remove this as and when possible
			NodeSubscriptionImpl subscription = (NodeSubscriptionImpl) subscriptions
					.next();
			item = query.addElement("item");
			item.addNamespace("ns1", JabberPubsub.NAMESPACE_URI);
			item.addNamespace("ns2", JabberPubsub.NAMESPACE_URI);
			item.addAttribute("jid", fetchersJid.toBareJID());
			item.addAttribute("node", subscription.getNode());
			item.addAttribute("ns1:affiliation", subscription.getAffiliation());
			item.addAttribute("ns2:subscription",
					subscription.getSubscription());
		}
	}

	private void missingJidRequest() {
		createExtendedErrorReply(PacketError.Type.modify,
				PacketError.Condition.bad_request, "nodeid-required");
	}

	private void createExtendedErrorReply(Type type, Condition condition,
			String additionalElement) {
		reply.setType(IQ.Type.error);
		Element standardError = new DOMElement(condition.toString(),
				new org.dom4j.Namespace("", JabberPubsub.NS_XMPP_STANZAS));
		Element extraError = new DOMElement(additionalElement,
				new org.dom4j.Namespace("", JabberPubsub.NS_PUBSUB_ERROR));
		Element error = new DOMElement("error");
		error.addAttribute("type", type.toString());
		error.add(standardError);
		error.add(extraError);
		reply.setChildElement(error);
	}

	public boolean accept(Element elm) {
		return elm.getName().equals("items");
	}
}