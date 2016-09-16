package com.sheraz.aws.lambda;

import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.util.StringUtils;
import com.sheraz.aws.lambda.EC2Client.EC2InstanceStateChangeEvent;

/**
 * Lambda function that is triggered on "RunInstance" event. It will create a DNS resource record
 * in the provided Route53 zone ID. Some key points of functionality are:
 * 
 * * Zone ID can be located in a cross account that that lambda function has permissions to access via STS
 *   This means you can have resources from multiple accounts create dns entries in a single production account
 *   Route53 zone.
 *    
 * * By default will look for zone ID in current account (account hosting lambda function), if zone not found
 *   it reverts to looking in cross account based on hard-coded ARN for now. In future, would be nice to parameterize that.
 * 
 * * It is assumed that the hostname of the EC2 instance is present in the value of the "Name" tag
 * 
 * * DNS records are created for only instances that are linux (i.e. have lx in the hostname) and are not
 *   present in an auto-scaling group. Windows instances are auto domain joined through a different script.
 *    
 * @author Sheraz Khan
 *
 */
public class Route53DNSUpdater 
{

	private static final String HOSTED_ZONE_ID = "Z1BRVK3TABIDQ9"; //test zone: "ZBYAFU3GVKHJF";
	private static final String PRODUCTION_CROSS_ACCOUNT_ROLE_ARN = "arn:aws:iam::467936237394:role/CrossAccount-UpdateRoute53-PrivateAWSZone";
	private static final String LINUX_HOSTNAME_PREFIX = "lx238";
	private static final String TAG_WITH_HOSTNAME = "Name";
	
	public void handleRoute53DNSUpdates(EC2InstanceStateChangeEvent event)
	{
		Instance instance = fetchEC2Instance(event);
		String hostname = retrieveInstanceHostName(instance);
		if(isLinuxInstanceWithCorrectNamingConvention(hostname))
		{
			Route53Client route53 = new Route53Client(HOSTED_ZONE_ID, PRODUCTION_CROSS_ACCOUNT_ROLE_ARN);
			performRoute53DNSUpdate(route53, getInstanceIPAddress(instance), hostname);
		}
	}
	
	public void handleRoute53DNSRemove(EC2InstanceStateChangeEvent event)
	{
		Instance instance = fetchEC2Instance(event);
		String hostname = retrieveInstanceHostName(instance);
		if(isLinuxInstanceWithCorrectNamingConvention(hostname))
		{
		    Route53Client route53 = new Route53Client(HOSTED_ZONE_ID, PRODUCTION_CROSS_ACCOUNT_ROLE_ARN);
	        route53.removeDNSRecord(hostname);
		}
	}
	
	private boolean isLinuxInstanceWithCorrectNamingConvention(String hostname)
	{
		return !StringUtils.isNullOrEmpty(hostname) &&
		        hostname.toLowerCase().startsWith(LINUX_HOSTNAME_PREFIX) &&
		       !hostname.contains(" ");
	}
	
	private void performRoute53DNSUpdate(Route53Client route53, String ipAddress, String hostname)
	{
		if(! route53.targetHostedZoneFound())
		{
			throw new RuntimeException("Zone with ID " + HOSTED_ZONE_ID + " not found. Did not create DNS record.");
		}
		route53.updateDNSRecord(ipAddress, hostname);
	}
	
	private Instance fetchEC2Instance(EC2InstanceStateChangeEvent event)
	{
		EC2Client ec2 = new EC2Client(event);
		return ec2.describeInstance(event.getInstanceId());
	}
	
	private String getInstanceIPAddress(Instance instance)
	{
		return instance.getPrivateIpAddress();
	}
	
	private String retrieveInstanceHostName(Instance instance)
	{
		String hostname = null;
		for(Tag tag : instance.getTags())
		{
			if(tag.getKey().equals(TAG_WITH_HOSTNAME))
			{
				hostname = tag.getValue(); 
				break;
			}
		}
		if(StringUtils.isNullOrEmpty(hostname))
		{
			System.out.println("The <Name> tag is not present or is empty. Can't retrieve hostname.");
			return null;
		}
		return hostname.trim();
	}
	
}