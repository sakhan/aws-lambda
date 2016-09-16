package com.sheraz.aws.lambda;

import java.util.ArrayList;
import java.util.List;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.sns.AmazonSNSClient;
import com.amazonaws.services.sns.model.CreateTopicResult;
import com.sheraz.aws.lambda.EC2Client.EC2InstanceStateChangeEvent;

/**
 * This lambda enabled class is responsible for enforcing a "Tag or stop" policy.
 * A courtesy email will be sent to the owner and cloud-services team.
 * 
 * The following tag rules are enforced:
 * 
 * - Ensure tags are present: PO_Number, Application_Name, Owner, Approver 
 * - If Cost_Center is present convert to PO_Number
 * 
 * Improvements: 
 * 
 * How do we validate that the PO_Number provided is actually a legitimate PO?
 * 
 * @author Sheraz Khan
 *
 */
public class EC2InstanceTagComplianceChecker 
{
	
	private static final String SNS_TOPIC_NAME = "Lambda-EC2InstanceTagCompliance";
	private static final long   WAIT_DURATION = 5 * 1000; 
	
	private static final String PO_NUMBER = "PO_Number";
	private static final String COST_CENTER = "Cost_Center";
	private static final String APPLICATION_NAME = "Application_Name";
	private static final String APPROVER = "Approver";
	private static final String OWNER = "Owner";
	private static final String NAME = "Name";
	
	private EC2InstanceStateChangeEvent _runInstanceEvent;
	private List<String>                _messages;    			  
	
	public void handleEC2InstanceTagCompliance(EC2InstanceStateChangeEvent event)
	{
		System.out.println("Handling event id: " + event.getId());
		performInit(event);
		waitForAnyPostTaggingToComplete();
		EC2Client ec2Client = createEC2Client(event);
		processTagOrStopInstancePolicy(ec2Client, event.getInstanceId());
	}
	
	private void performInit(EC2InstanceStateChangeEvent event)
	{
		_runInstanceEvent = event;
		_messages = new ArrayList<String>();
	}
	
	private void waitForAnyPostTaggingToComplete()
	{
		try {
			Thread.sleep(WAIT_DURATION);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}
	
	private EC2Client createEC2Client(EC2InstanceStateChangeEvent event)
	{
		return new EC2Client(event);
	}
	
	private void processTagOrStopInstancePolicy(EC2Client ec2Client, String instanceId)
	{
		Instance instance = ec2Client.describeInstance(instanceId);
		List<Tag> tags = instance.getTags();
		
		if(! checkRequiredTagsArePresent(tags))
		{
			//ec2Client.stopInstance(instanceId); // TODO: temporarily remove this. Should schedule for stopping at future date via tags
			notifyOwners(instanceId, tags);
			logMessages(_messages);
		}
	}
	
	private boolean checkRequiredTagsArePresent(List<Tag> tags)
	{
		return ( hasPOTag(tags) & hasTag(tags, APPLICATION_NAME) & 
				 hasTag(tags, APPROVER) & hasTag(tags, OWNER) & hasTag(tags, NAME) );
	}
	
	private boolean hasPOTag(List<Tag> tags)
	{
	    return hasTag(tags, PO_NUMBER) || hasTag(tags, COST_CENTER);
	}
	
	private boolean hasTag(List<Tag> tags, String tagKeyToCheck)
	{
		for(Tag tag : tags)
		{
			String key = tag.getKey().trim();
			String value = tag.getValue() != null ? tag.getValue().trim() : "";
			if(key.equals(tagKeyToCheck) && value.length() > 0) return true;
		}
		_messages.add("Please provide missing tag: " + tagKeyToCheck);
		return false;
	}
	
	private void notifyOwners(String instanceId, List<Tag> tags)
	{
		AmazonSNSClient sns = new AmazonSNSClient();
		sns.configureRegion(Regions.fromName(_runInstanceEvent.getRegion())); 
		
		CreateTopicResult topicResult = sns.createTopic(SNS_TOPIC_NAME);
		sns.publish(topicResult.getTopicArn(), createNotificationMessage(tags), "ALERT: EC2 instance [" + instanceId + "] has non-compliant tagging");
		System.out.println("Email notification has been sent to SNS topic: " + SNS_TOPIC_NAME);
	}
	
	private String createNotificationMessage(List<Tag> tags)
	{
		StringBuffer msg = new StringBuffer();
		msg.append("As a curtosy, the EC2 instance with id [")
		   .append(_runInstanceEvent.getInstanceId())
		   .append("] was NOT prevented from being started, but the tags are non-compliant and need to be corrected.\n\n");
		
		msg.append("Currently the tags look like: \n\n");
		for(Tag tag : tags) msg.append(tag.getKey() + ": " + tag.getValue()).append("\n");
		
		msg.append("\nPlease correct the following tag requirements:\n\n");
		for(String error : _messages) msg.append(" * ").append(error).append("\n");
		
		msg.append("\nWith love from the Cloud Services team! :)\n");	
        
		return msg.toString();
	}
	
	private void logMessages(List<String> messages)
	{
		for(String msg : messages) System.out.println(msg);
	}
	
}
