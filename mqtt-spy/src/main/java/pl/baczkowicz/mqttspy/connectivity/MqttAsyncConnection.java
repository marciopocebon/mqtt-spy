/***********************************************************************************
 * 
 * Copyright (c) 2014 Kamil Baczkowicz
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 *
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v10.html
 *    
 * The Eclipse Distribution License is available at
 *   http://www.eclipse.org/org/documents/edl-v10.php
 *
 * Contributors:
 * 
 *    Kamil Baczkowicz - initial API and implementation and/or initial documentation
 *    
 */
package pl.baczkowicz.mqttspy.connectivity;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import pl.baczkowicz.mqttspy.common.generated.ScriptDetails;
import pl.baczkowicz.mqttspy.configuration.ConfigurationManager;
import pl.baczkowicz.mqttspy.configuration.UiProperties;
import pl.baczkowicz.mqttspy.connectivity.reconnection.ReconnectionManager;
import pl.baczkowicz.mqttspy.logger.MqttMessageLogger;
import pl.baczkowicz.mqttspy.scripts.FormattingManager;
import pl.baczkowicz.mqttspy.scripts.InteractiveScriptManager;
import pl.baczkowicz.mqttspy.scripts.Script;
import pl.baczkowicz.mqttspy.stats.StatisticsManager;
import pl.baczkowicz.mqttspy.storage.FormattedMqttMessage;
import pl.baczkowicz.mqttspy.storage.ManagedMessageStoreWithFiltering;
import pl.baczkowicz.mqttspy.ui.events.EventManager;
import pl.baczkowicz.mqttspy.ui.events.queuable.EventQueueManager;
import pl.baczkowicz.mqttspy.utils.ConversionUtils;

/**
 * Asynchronous MQTT connection with the extra UI elements required.
 */
public class MqttAsyncConnection extends MqttConnectionWithReconnection
{
	private final static Logger logger = LoggerFactory.getLogger(MqttAsyncConnection.class);

	private final RuntimeConnectionProperties properties;
	
	private boolean isOpened;
	
	private boolean isOpening;
	
	private final ManagedMessageStoreWithFiltering store;

	/** Maximum number of messages to store for this connection in each message store. */
	private int preferredStoreSize;

	private StatisticsManager statisticsManager;

	private final EventManager eventManager;

	private final InteractiveScriptManager scriptManager;

	private MqttMessageLogger messageLogger;

	public MqttAsyncConnection(final ReconnectionManager reconnectionManager, final RuntimeConnectionProperties properties, 
			final MqttConnectionStatus status, final EventManager eventManager, 
			final InteractiveScriptManager scriptManager, final FormattingManager formattingManager,
			final EventQueueManager uiEventQueue, final ConfigurationManager configurationManager)
	{ 
		super(reconnectionManager, properties);
		
		// Max size is double the preferred size
		store = new ManagedMessageStoreWithFiltering(properties.getName(), 
				properties.getConfiguredProperties().getMinMessagesStoredPerTopic(), 
				properties.getMaxMessagesStored(), 
				properties.getMaxMessagesStored() * 2, 
				uiEventQueue, eventManager,
				formattingManager,
				UiProperties.getSummaryMaxPayloadLength(configurationManager));
		
		this.setPreferredStoreSize(properties.getMaxMessagesStored());
		this.properties = properties;
		this.eventManager = eventManager;
		this.scriptManager = scriptManager;
		setConnectionStatus(status);
	}
	
	public void messageReceived(final FormattedMqttMessage receivedMessage)
	{		
		// TODO: we should only delete from the topic matcher when a subscription is closed for good, not when just unsubscribed
		final List<String> matchingSubscriptionTopics = getTopicMatcher().getMatchingSubscriptions(receivedMessage.getTopic());
					
		final FormattedMqttMessage message = new FormattedMqttMessage(receivedMessage);		
		
		final List<String> matchingActiveSubscriptions = new ArrayList<String>();
		
		final BaseMqttSubscription lastMatchingSubscription = 
				matchMessageToSubscriptions(matchingSubscriptionTopics, receivedMessage, matchingActiveSubscriptions);
		
		// If logging is enabled
		if (messageLogger != null && messageLogger.isRunning())
		{
			message.setMatchingSubscriptionTopics(matchingActiveSubscriptions);
			messageLogger.getQueue().add(message);
		}
		
		statisticsManager.messageReceived(getId(), matchingActiveSubscriptions);

		if (lastMatchingSubscription != null)
		{
			message.setSubscription(lastMatchingSubscription.getTopic());
		} 
		else
		{
			logger.warn("Cannot find a matching subscription for " + receivedMessage.getTopic());
		}
		
		// Pass the message to the "all" message store		
		store.messageReceived(message);
	}
	
	private BaseMqttSubscription matchMessageToSubscriptions(final List<String> matchingSubscriptionTopics, final FormattedMqttMessage receivedMessage, 
			final List<String> matchingActiveSubscriptions)
	{
		BaseMqttSubscription lastMatchingSubscription = matchMessageToSubscriptions(
				matchingSubscriptionTopics, receivedMessage, matchingActiveSubscriptions, false);
		
		// If no active subscriptions available, use the first one that matches (as we might be still receiving messages for a non-active subscription)
		if (matchingActiveSubscriptions.isEmpty())
		{
			logger.debug("No active subscription available for {}, trying to find first matching...", receivedMessage.getTopic());
			lastMatchingSubscription = matchMessageToSubscriptions(matchingSubscriptionTopics, receivedMessage, matchingActiveSubscriptions, true);
			logger.debug("First matching = {} {}", lastMatchingSubscription, matchingSubscriptionTopics);
		}
		
		return lastMatchingSubscription;
	}
	
	private BaseMqttSubscription matchMessageToSubscriptions(final List<String> matchingSubscriptionTopics, final FormattedMqttMessage receivedMessage, 
			final List<String> matchingSubscriptions, final boolean anySubscription)
	{
		BaseMqttSubscription foundMqttSubscription = null;
		
		// For all found subscriptions
		for (final String matchingSubscriptionTopic : matchingSubscriptionTopics)
		{					
			// Get the mqtt-spy's subscription object
			final BaseMqttSubscription mqttSubscription = subscriptions.get(matchingSubscriptionTopic);

			// If a match has been found, and the subscription is active or we don't care
			if (mqttSubscription != null && (anySubscription || mqttSubscription.isSubscribing() || mqttSubscription.isActive()))
			{
				matchingSubscriptions.add(matchingSubscriptionTopic);

				// Create a copy of the message for each subscription
				final FormattedMqttMessage message = new FormattedMqttMessage(receivedMessage);
				
				// Set subscription reference on the message
				message.setSubscription(mqttSubscription.getTopic());
				foundMqttSubscription = mqttSubscription;
				
				if (mqttSubscription.getDetails() != null 
						&& mqttSubscription.getDetails().getScriptFile() != null 
						&& !mqttSubscription.getDetails().getScriptFile().isEmpty())
				{
					scriptManager.runScriptFileWithReceivedMessage(mqttSubscription.getDetails().getScriptFile(), message);
				}
				
				// Pass the message for subscription handling
				mqttSubscription.getStore().messageReceived(message);
				
				// Find only one matching subscription if checking non-active ones
				if (anySubscription)
				{
					break;
				}
			}
		}			
		
		return foundMqttSubscription;
	}
	
	public boolean publish(final String publicationTopic, final String data, final int qos, final boolean retained)
	{
		return publish(publicationTopic, ConversionUtils.stringToArray(data), qos, retained);
	}
	
	public boolean publish(final String publicationTopic, final byte[] data, final int qos, final boolean retained)
	{
		if (canPublish())
		{
			try
			{
				logger.info("Publishing message on topic \"" + publicationTopic + "\". Payload size = \"" + data.length + "\"");
				client.publish(publicationTopic, data, qos, retained);
				
				logger.trace("Published message on topic \"" + publicationTopic + "\". Payload size = \"" + data.length + "\"");
				statisticsManager.messagePublished(getId(), publicationTopic);
				
				return true;
			}
			catch (MqttException e)
			{
				logger.error("Cannot publish message on " + publicationTopic, e);
			}
		}
		else
		{
			logger.warn("Publication attempt failure - no connection available...");
		}
		
		return false;
	}

	public void connectionLost(Throwable cause)
	{
		super.connectionLost(cause);
		unsubscribeAll(false);
	}

	public void startBackgroundScripts()	
	{
		final boolean firstConnection = getConnectionAttempts() == 1;
		
		// Attempt starting background scripts
		if (firstConnection)
		{
			for (final ScriptDetails scriptDetails : properties.getConfiguredProperties().getBackgroundScript())
			{
				final File scriptFile = new File(scriptDetails.getFile());							
				final Script script = scriptManager.getScripts().get(Script.getScriptIdFromFile(scriptFile));
				
				if (scriptDetails.isAutoStart() && scriptFile.exists() && script != null)
				{					
					scriptManager.runScript(script, true);
				}
				else if (!scriptFile.exists())
				{
					logger.warn("File " + scriptDetails.getFile() + " does not exist");
				}
				else if (script == null)
				{
					logger.warn("Couldn't retrieve a script for " + scriptDetails.getFile());
				}					
			}
		}
	}

	public boolean resubscribeAll(final boolean requestedOnly)
	{
		final boolean firstConnection = getConnectionAttempts() == 1;
		final boolean resubscribeEnabled = connectionDetails.getReconnectionSettings() != null 
				&& connectionDetails.getReconnectionSettings().isResubscribe();
		
		final boolean tryAutoSubscribe = firstConnection || resubscribeEnabled;
			
		// Attempt re-subscription
		for (final BaseMqttSubscription subscription : subscriptions.values())
		{
			logger.debug("Subscription {} status [requestedOnly = {}, firstConnection = {}, resubscribeEnabled = {}, subscriptionRequested = {}", 
					subscription.getTopic(), requestedOnly, firstConnection, resubscribeEnabled, subscription.getSubscriptionRequested());
			
			if (!requestedOnly || (tryAutoSubscribe && subscription.getSubscriptionRequested()))
			{
				resubscribe(subscription);
			}
		}		

		return true;
	}

	public boolean resubscribe(final BaseMqttSubscription subscription)
	{
		return subscribe(subscription);
	}


	public boolean unsubscribeAll(final boolean manualOverride)
	{
		for (final BaseMqttSubscription subscription : subscriptions.values())
		{
			unsubscribe(subscription, manualOverride);
		}

		return true;
	}

	public boolean unsubscribe(final BaseMqttSubscription subscription, final boolean manualOverride)
	{
		// If this is a user action, set it not to auto-subscribe
		if (manualOverride && subscription.getSubscriptionRequested())
		{
			subscription.setSubscriptionRequested(false);
		}
		
		// If already unsubscribed, ignore
		if (!subscription.isActive())
		{
			return false;
		}

		logger.debug("Unsubscribing from " + subscription.getTopic());
		final boolean unsubscribed = unsubscribe(subscription.getTopic());
		
		subscription.setActive(false);
		logger.trace("Subscription " + subscription.getTopic() + " is active = " + subscription.isActive());

		return unsubscribed;
	}

	public boolean unsubscribeAndRemove(final BaseMqttSubscription subscription)
	{
		final boolean unsubscribed = unsubscribe(subscription, true);
		removeSubscription(subscription);
		logger.info("Subscription " + subscription.getTopic() + " removed");
		return unsubscribed;
	}
	
	@Override
	public boolean subscribe(final BaseMqttSubscription subscription)
	{
		final boolean subscribed = super.subscribe(subscription);
		
		if (subscribed)
		{
			StatisticsManager.newSubscription();
		}
		
		return subscribed;
	}

	public void setConnectionStatus(MqttConnectionStatus connectionStatus)
	{
		super.setConnectionStatus(connectionStatus);
		eventManager.notifyConnectionStatusChanged(this);
	}

	public RuntimeConnectionProperties getProperties()
	{
		return properties;
	}

	public Map<String, BaseMqttSubscription> getSubscriptions()
	{
		return subscriptions;
	}

	public int getPreferredStoreSize()
	{
		return preferredStoreSize;
	}

	public void setPreferredStoreSize(int preferredStoreSize)
	{
		this.preferredStoreSize = preferredStoreSize;
	}
	
	public int getId()
	{
		return properties.getId();
	}

	public boolean isOpened()
	{
		return isOpened;
	}
	
	public void closeConnection()
	{
		setOpened(false);
	}

	public void setOpened(boolean isOpened)
	{
		this.isOpened = isOpened;
		
		eventManager.notifyConnectionStatusChanged(this);
	}

	public boolean isOpening()
	{
		return isOpening;
	}

	public void setOpening(boolean isOpening)
	{
		this.isOpening = isOpening;
		eventManager.notifyConnectionStatusChanged(this);
	}

	public ManagedMessageStoreWithFiltering getStore()
	{
		return store;
	}

	public String getName()
	{
		return store.getName();
	}	

	public void setStatisticsManager(final StatisticsManager statisticsManager)
	{
		this.statisticsManager = statisticsManager;
	}

	public InteractiveScriptManager getScriptManager()
	{
		return this.scriptManager;
	}

	public void setMessageLogger(final MqttMessageLogger messageLogger)
	{
		this.messageLogger = messageLogger;
	}
	
	public MqttMessageLogger getMessageLogger()
	{
		return messageLogger;
	}
}
