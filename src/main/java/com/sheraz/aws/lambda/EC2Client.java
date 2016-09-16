package com.sheraz.aws.lambda;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.CreateTagsRequest;
import com.amazonaws.services.ec2.model.DeleteTagsRequest;
import com.amazonaws.services.ec2.model.DeleteVolumeRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.DescribeVolumesRequest;
import com.amazonaws.services.ec2.model.DescribeVolumesResult;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.StopInstancesRequest;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.Volume;
import com.amazonaws.util.StringUtils;

/**
 * Wrapper calls for AmazonEC2Client to abstract out and simplify low level calls.
 * Particularly tailored for CloudWatch EC2 RunInstance event.
 * 
 * @author Sheraz Khan
 *
 */
public class EC2Client 
{
	
	private CloudWatchScheduleEvent     _cloudWatchEvent;
	private AmazonEC2Client			    _amazonEC2;
	
	public EC2Client(CloudWatchScheduleEvent cloudWatchEvent)
	{
		_cloudWatchEvent = cloudWatchEvent;
		createEC2Client(cloudWatchEvent.getRegion());
	}
	
	private void createEC2Client(String region)
	{
		if(StringUtils.isNullOrEmpty(region)) throw new RuntimeException("Region is blank, cannot create EC2 client.");
		_amazonEC2 = new AmazonEC2Client();
		_amazonEC2.configureRegion(Regions.fromName(region));
	}
	
	public Instance describeInstance(String instanceId) 
	{
		System.out.println("Describing instance-id: " + instanceId + ", in region: " + _cloudWatchEvent.getRegion());
		
		DescribeInstancesRequest request = new DescribeInstancesRequest();
		request.withInstanceIds(instanceId);
		DescribeInstancesResult result = _amazonEC2.describeInstances(request);
		return retrieveInstanceFromInstancesResult(result);
	}
	
	private Instance retrieveInstanceFromInstancesResult(DescribeInstancesResult instancesResult)
	{
		List<Reservation> reservations = instancesResult.getReservations();
		if(reservations.isEmpty()) throw new RuntimeException("No reservations/instances were found."); 
		
		Reservation reservation = reservations.get(0);
		List<Instance> instances = reservation.getInstances();
		
		if(instances.isEmpty()) throw new RuntimeException("No instances were found.");
		
		Instance instance = instances.get(0);
		return instance;
	}
	
	public void stopInstance(String instanceId)
	{
		System.out.println("***[NOTE] Stopping instance: " + instanceId + " ***");
		StopInstancesRequest request = new StopInstancesRequest();
		request.setInstanceIds(Arrays.asList(instanceId));
		_amazonEC2.stopInstances(request);
	}
	
	public List<Volume> describeAllDetachedVolumes()
	{
	    DescribeVolumesRequest volumeRequest = new DescribeVolumesRequest();
	    Filter filter = new Filter();
	    filter.withName("status").withValues("available");
	    volumeRequest.withFilters(filter);
	    DescribeVolumesResult volumeResult =_amazonEC2.describeVolumes(volumeRequest);
	    return volumeResult.getVolumes();
	}
	
	public void tagResource(String resourceId, Tag tag)
	{
	    CreateTagsRequest tagRequest = new CreateTagsRequest();
	    tagRequest.withResources(resourceId).withTags(tag);
	    _amazonEC2.createTags(tagRequest);
	}
	
	public void untagResource(String resourceId, Tag tag)
	{
	    DeleteTagsRequest tagRequest = new DeleteTagsRequest();
	    tagRequest.withResources(resourceId).withTags(tag);
	    _amazonEC2.deleteTags(tagRequest);
	}
	
	public void deleteVolumes(List<String> volumeIds)
	{
	    if(volumeIds == null || volumeIds.isEmpty()) return;
	    
	    System.out.println("Deleting " + volumeIds.size() + " Volumes.");
	    for(String volumeId : volumeIds)
	    {
	        System.out.println("Deleting EBS volume: " + volumeId);
	        _amazonEC2.deleteVolume(new DeleteVolumeRequest(volumeId));
	    }
	}
	
	public static class EC2InstanceStateChangeEvent extends CloudWatchScheduleEvent 
	{
		public String getInstanceId() {
		    Map<String, String> detail = getDetail();
			return detail != null ? detail.get("instance-id") : null;
		}
	}
	
	public static class CloudWatchScheduleEvent
	{
	    private String id;
        private String region;
        private String account;
        
        private Map<String, String> detail;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getAccount() {
            return account;
        }

        public void setAccount(String account) {
            this.account = account;
        }

        public Map<String, String> getDetail() {
            return detail;
        }

        public void setDetail(Map<String, String> detail) {
            this.detail = detail;
        }
	}
}
