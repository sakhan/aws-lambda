package com.sheraz.aws.lambda;

import java.util.Arrays;
import java.util.List;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.services.route53.AmazonRoute53Client;
import com.amazonaws.services.route53.model.Change;
import com.amazonaws.services.route53.model.ChangeAction;
import com.amazonaws.services.route53.model.ChangeBatch;
import com.amazonaws.services.route53.model.ChangeResourceRecordSetsRequest;
import com.amazonaws.services.route53.model.ChangeResourceRecordSetsResult;
import com.amazonaws.services.route53.model.HostedZone;
import com.amazonaws.services.route53.model.ListResourceRecordSetsRequest;
import com.amazonaws.services.route53.model.ListResourceRecordSetsResult;
import com.amazonaws.services.route53.model.RRType;
import com.amazonaws.services.route53.model.ResourceRecord;
import com.amazonaws.services.route53.model.ResourceRecordSet;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClient;
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest;
import com.amazonaws.services.securitytoken.model.AssumeRoleResult;
import com.amazonaws.services.securitytoken.model.Credentials;

/**
 * A wrapper to AmazonRoute53 class in order to abstract and simplify low level calls to AmazonRoute53.
 * 
 * The scope of this class is for a given Route53 Hosted Zone. Also has cross-account fallback behavior (via STS)
 * in case a single Zone is being used across multiple accounts e.g. prod and non-prod.
 * 
 * @author Sheraz Khan
 *
 */
public class Route53Client 
{
	private AmazonRoute53Client _amazonRoute53;
	private HostedZone 	        _targetHostedZone;
	private String              _targetHostedZoneId;
	private String              _crossAccountRoleARN;
	
	public Route53Client(String targetZoneId, String crossAccountRoleARN)
	{
		_targetHostedZoneId = targetZoneId;
		_crossAccountRoleARN = crossAccountRoleARN;
		
		// first we try current account i.e. account hosting the lambda function
		_targetHostedZone = initRoute53IfZoneInDefaultAccount(targetZoneId); 
		
		if(_targetHostedZone == null)
		{
			_targetHostedZone = initRoute53ByZoneInCrossAccount(targetZoneId);
		}
	}
	
	public boolean targetHostedZoneFound()
	{
		return _targetHostedZone != null;
	}
	
	private HostedZone initRoute53IfZoneInDefaultAccount(String targetZoneId)
	{
		_amazonRoute53 = new AmazonRoute53Client();
		return findTargetHostedZone(_amazonRoute53, targetZoneId);
	}
	
	private HostedZone initRoute53ByZoneInCrossAccount(String targetZoneId)
	{
		AWSSecurityTokenServiceClient stsClient = new AWSSecurityTokenServiceClient();
		AssumeRoleResult assumeRoleResult = stsClient.assumeRole(new AssumeRoleRequest()
				        .withRoleArn(_crossAccountRoleARN)
						.withRoleSessionName("Lambda-CreateRoute53DNSRecord-NonProd"));
		AWSCredentials credentials = createAWSCredentials(assumeRoleResult.getCredentials());
		_amazonRoute53 = new AmazonRoute53Client(credentials);
		return findTargetHostedZone(_amazonRoute53, targetZoneId);
	}
	
	private AWSCredentials createAWSCredentials(Credentials credentials)
	{
		return new BasicSessionCredentials(credentials.getAccessKeyId(), 
				                           credentials.getSecretAccessKey(), 
				                           credentials.getSessionToken());
	}
	
	private HostedZone findTargetHostedZone(AmazonRoute53Client route53, String targetZoneId)
	{
		List<HostedZone> zones = route53.listHostedZones().getHostedZones();
		for(HostedZone zone : zones)
		{
			if(zone.getId().equals(targetZoneId)) return zone;
		}
		return null;
	}
	
	public void updateDNSRecord(String instanceIPAddress, String hostname)
	{
		String qualifiedHostname = getFullyQualifiedHostName(hostname);
		ResourceRecord resourceRecord = new ResourceRecord(instanceIPAddress);
		ResourceRecordSet resourceRecordSet = new ResourceRecordSet();
		resourceRecordSet.withName(qualifiedHostname)
		                 .withType(RRType.A)
		                 .withResourceRecords(Arrays.asList(resourceRecord))
		                 .withTTL(new Long(300));
		Change change = new Change(ChangeAction.UPSERT, resourceRecordSet);
		
		ChangeResourceRecordSetsRequest request = new ChangeResourceRecordSetsRequest();
		request.withHostedZoneId(_targetHostedZoneId)
		       .withChangeBatch(new ChangeBatch(Arrays.asList(change)));

		ChangeResourceRecordSetsResult result = _amazonRoute53.changeResourceRecordSets(request);
		System.out.println("Route53: submitted type-A DNS record for [" + instanceIPAddress + " = " + qualifiedHostname +"]");
		System.out.println(result.getChangeInfo().toString());
	}
	
	private String getFullyQualifiedHostName(String hostname)
	{
		return hostname + "." + _targetHostedZone.getName();
	}
	
	public void removeDNSRecord(String hostname)
	{
		ResourceRecordSet resourceRecordSet = findResourceRecordSet(hostname);
		if(resourceRecordSet == null) 
		{
			System.out.println("Route 53: Could not find DNS record for " + hostname + 
					           " in zone: " + _targetHostedZone.getName() + " - no record removed.");
			return;
		}
		performResourceRecordSetDelete(resourceRecordSet);
	}
	
	private void performResourceRecordSetDelete(ResourceRecordSet resourceRecordSet)
	{
	    Change change = new Change(ChangeAction.DELETE, resourceRecordSet);
        ChangeResourceRecordSetsRequest changeRequest = new ChangeResourceRecordSetsRequest();
        changeRequest.withHostedZoneId(_targetHostedZoneId)
                     .withChangeBatch(new ChangeBatch(Arrays.asList(change)));
        ChangeResourceRecordSetsResult result = _amazonRoute53.changeResourceRecordSets(changeRequest);
        System.out.println("Route 53: removed type-A DNS record [" + resourceRecordSet.getName() + "]");
        System.out.println(result.getChangeInfo().toString());
	}
	
	private ResourceRecordSet findResourceRecordSet(String hostname)
	{
		String fullyQualifiedHostname = getFullyQualifiedHostName(hostname);
		ListResourceRecordSetsRequest recordSetsRequest = new ListResourceRecordSetsRequest();
		recordSetsRequest.withHostedZoneId(_targetHostedZoneId)
		                 .withStartRecordName(fullyQualifiedHostname)
		                 .withStartRecordType(RRType.A);
		ListResourceRecordSetsResult result = _amazonRoute53.listResourceRecordSets(recordSetsRequest);
		for(ResourceRecordSet record : result.getResourceRecordSets())
		{
			if(record.getName().equals(fullyQualifiedHostname)) return record;
		}
		return null;
	}
}

