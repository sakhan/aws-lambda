package com.sheraz.aws.lambda;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.Volume;
import com.amazonaws.services.sns.AmazonSNSClient;
import com.amazonaws.services.sns.model.CreateTopicResult;
import com.sheraz.aws.lambda.EC2Client.CloudWatchScheduleEvent;

/**
 * DetachedVolumeJanitor contains a set of AWS lambda functions that keep your environment clean and tidy when
 * it comes to detached EBS volumes. The general pattern is a 3 stage approach: mark, notify and delete.
 * 
 * 1. This first function handleDetachedVolumeScheduleDeleteStamp(), periodically scouts for detached volumes 
 *    and schedules them for deletion on a future date by tagging the volume with a “delete-scheduled-on” 
 *    timestamp. For now this is configured for 30 days in the future.
 * 
 * 2. The second lambda function handleDetachedVolumeNotifyAndDelete(), periodically warns via SNS 
 *    notifications of any upcoming scheduled deletions. For now this is configured to include volumes scheduled 
 *    within the next 7 days.
 * 
 * 3. The second lambda function (combined them together, can be its own function as well), deletes all volumes 
 *    that have a “delete-scheduled-on” date in the past. Also sends out a confirmation notification with deleted 
 *    volume Id's listed.
 *  
 * If someone wants to save a particular volume from deletion you can either remove the “delete-scheduled-on” tag 
 * (this will delay deletion for another 30 days), or modify the date to some large date in the future.
 *  
 * @author Sheraz Khan
 */
public class DetachedVolumeJanitor 
{
    private static final String            SCHEDULE_DELETE_TAG = "lambda:DetachedVolumeJanitor:delete-scheduled-on";
    private static final String            SNS_TOPIC_DELETE_VOLUMES = "Lambda-DetachedVolumeJanitor";
    private static final int               DETACHED_VOLUME_RETENTION_DAYS = 30;
    private static final int               SCHEDULED_DELETION_NOTIFY_DAYS = 7;
    
    private final SimpleDateFormat         _dateFromatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
    
    public void handleDetachedVolumeScheduleDeleteStamp(CloudWatchScheduleEvent event)
    {
        EC2Client ec2Client = new EC2Client(event);
        List<Volume> detachedVolumes = ec2Client.describeAllDetachedVolumes();
        markVolumesForDeletion(ec2Client, detachedVolumes, DETACHED_VOLUME_RETENTION_DAYS);
    }
    
    public void handleDetachedVolumeNotifyAndDelete(CloudWatchScheduleEvent event)
    {
        EC2Client ec2Client = new EC2Client(event);
        List<Volume> detachedVolumes = ec2Client.describeAllDetachedVolumes();
        notifyVolumesScheduledForFutureDeletion(event, detachedVolumes, SCHEDULED_DELETION_NOTIFY_DAYS);
        deleteVolumesScheduledForDeletion(event, ec2Client, detachedVolumes);
    }
    
    /**
     * Helpful for testing purposes, so you can reset/clear any volumes tagged for deletion.
     */
    public void handleDetachedVolumeClearScheduleDeleteTag(CloudWatchScheduleEvent event)
    {
        EC2Client ec2Client = new EC2Client(event);
        List<Volume> detachedVolumes = ec2Client.describeAllDetachedVolumes();
        clearScheduledDeleteTag(ec2Client, detachedVolumes);
    }
    
    private void markVolumesForDeletion(EC2Client ec2Client, List<Volume> volumes, int retentionDays)
    {
        if(volumes == null || volumes.isEmpty()) return;
        int count = 0;
        for(Volume volume : volumes)
        {
            Date deleteOn = getScheduledForDeletionDate(volume);
            if(deleteOn == null)
            {
                tagVolumeForFutureDeletion(ec2Client, volume, retentionDays);
                count++;
            }
        }
        System.out.println("Marked " + count + " Volumes with scheduled for deletion tag.");
    }
    
    private void notifyVolumesScheduledForFutureDeletion(CloudWatchScheduleEvent event, List<Volume> volumes, int daysOut)
    {
        if(volumes == null || volumes.isEmpty()) return;
        
        Date today = new Date();
        List<Volume> notifyVolumes = new ArrayList<Volume>(volumes.size());
        for(Volume volume: volumes)
        {
            Date deleteOn = getScheduledForDeletionDate(volume);
            if(deleteOn == null) continue;
            Date notifyDate = adjustDateByDays(deleteOn, -1 * daysOut);
            if(today.after(notifyDate) && today.before(deleteOn))
            {
                notifyVolumes.add(volume);
            }
        }
        sendVolumeScheduledForDeletionNotification(event, notifyVolumes);
    }
    
    private void deleteVolumesScheduledForDeletion(CloudWatchScheduleEvent event, EC2Client ec2Client, List<Volume> volumes)
    {
        if(volumes == null || volumes.isEmpty()) return;
        
        Date today = new Date();
        List<String> deleteVolumeIds = new ArrayList<String>();
        for(Volume volume : volumes)
        {
            Date deleteOn = getScheduledForDeletionDate(volume);
            if(deleteOn != null && deleteOn.before(today)) deleteVolumeIds.add(volume.getVolumeId());
        }
        ec2Client.deleteVolumes(deleteVolumeIds); 
        sendVolumeDeleteConfirmationNotification(event, deleteVolumeIds);
    }
    
    private Date getScheduledForDeletionDate(Volume volume)
    {
        List<Tag> tags = volume.getTags();
        for(Tag tag : tags) 
        {
            if(tag.getKey().equals(SCHEDULE_DELETE_TAG))
            {
                try {
                    return _dateFromatter.parse(tag.getValue());
                } catch (ParseException e) {
                    return null;
                }
            }
        }
        return null;
    }
    
    private void tagVolumeForFutureDeletion(EC2Client ec2Client, Volume volume, int daysFromNow)
    {
        String deleteOnDate = getFormattedDateDaysFromToday(daysFromNow);
        Tag tag = new Tag(SCHEDULE_DELETE_TAG, deleteOnDate);
        ec2Client.tagResource(volume.getVolumeId(), tag);
    }
    
    private String getFormattedDateDaysFromToday(int days)
    {
        Date todayWithOffset = adjustDateByDays(new Date(), days);
        return _dateFromatter.format(todayWithOffset);
    }
    
    private void sendVolumeScheduledForDeletionNotification(CloudWatchScheduleEvent event, List<Volume> volumes)
    {
        if(volumes == null || volumes.isEmpty()) return;
        
        AmazonSNSClient sns = createAmazonSNSClient(event);
        CreateTopicResult topicResult = sns.createTopic(SNS_TOPIC_DELETE_VOLUMES);
        sns.publish(topicResult.getTopicArn(), createVolumeDeletionNotificationMessage(event, volumes), 
                    "[" + event.getAccount() + "] WARN: Detached Volumes Scheduled for Deletion");
    }
    
    private AmazonSNSClient createAmazonSNSClient(CloudWatchScheduleEvent event)
    {
        AmazonSNSClient sns = new AmazonSNSClient();
        sns.configureRegion(Regions.fromName(event.getRegion())); 
        return sns;
    }
    
    private String createVolumeDeletionNotificationMessage(CloudWatchScheduleEvent event, List<Volume> volumes)
    {
        StringBuffer msg = new StringBuffer();
        msg.append("Account: ").append(event.getAccount()).append(" (").append(event.getRegion()).append(")\n\n");
        msg.append("The following EBS Volumes are scheduled for deletion within the next " + SCHEDULED_DELETION_NOTIFY_DAYS + " days:\n\n");
        for(Volume volume : volumes)
        {
            msg.append(volume.getVolumeId()).append(" -> ").append(getScheduledForDeletionDate(volume)).append("\n");
            msg.append("Tags: ");
            for(Tag tag : volume.getTags()) msg.append("{"+ tag.getKey() + ": " + tag.getValue() + "} ");
            msg.append("\n\n");    
        }
        msg.append("If you would like to prevent a volume from deletion, you can remove the Volume tag: [" + SCHEDULE_DELETE_TAG + "]"
                   + " or set the tag value to a future date.\n\n");
        msg.append("-Cloud Services Team");
        return msg.toString();
    }
    
    private void sendVolumeDeleteConfirmationNotification(CloudWatchScheduleEvent event, List<String> volumeIds)
    {
        if(volumeIds == null || volumeIds.isEmpty()) return;
        AmazonSNSClient sns = createAmazonSNSClient(event);
        CreateTopicResult topicResult = sns.createTopic(SNS_TOPIC_DELETE_VOLUMES);
        sns.publish(topicResult.getTopicArn(), createVolumeDeleteConfirmationMessage(event, volumeIds), 
                    "[" + event.getAccount() + "] INFO: Detached Volumes Deletion Completed");
    }
    
    private String createVolumeDeleteConfirmationMessage(CloudWatchScheduleEvent event, List<String> volumeIds)
    {
        StringBuffer msg = new StringBuffer();
        msg.append("Account: ").append(event.getAccount()).append(" (").append(event.getRegion()).append(")\n\n");
        msg.append("The following EBS volumes have been deleted: \n\n");
        for(String volumeId : volumeIds) msg.append(volumeId).append("\n");
        msg.append("\nSorry if we deleted a volume you weren't ready to dispose off yet, it may still be "
                 + "retrievable from a nightly snapshot ... but we did send warnings! :)\n\n");
        msg.append("-Cloud Services Team\n");
        msg.append("000-CloudServices@corp.sysco.com");
        return msg.toString();
    }

    // TODO use util class
    private Date adjustDateByDays(Date date, int days)
    {
        Calendar cal = new GregorianCalendar();
        cal.setTime(date);
        cal.add(Calendar.DAY_OF_MONTH, days);
        return cal.getTime();
    }
    
    private void clearScheduledDeleteTag(EC2Client ec2Client, List<Volume> volumes)
    {
        Tag tag = new Tag();
        tag.setKey(SCHEDULE_DELETE_TAG);
        for(Volume volume : volumes) ec2Client.untagResource(volume.getVolumeId(), tag);
    }
    
}
