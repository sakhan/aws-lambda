Follow below steps to install/configure the lambda functions:
1. Create the required lambda roles via AWS Console
2. Upload deployment package to S3 
3. Create lambda functions by executing below CLI commands (replace sysco-non-prod profile name appropriately)
4. Create and configure CloudWatch event rules via AWS Console

aws lambda create-function ^
--region us-east-1 ^
--function-name EnforceTagCompliance ^
--description "Checks for required tags and sends warning notification via SNS if tags not compliant" ^
--code S3Bucket=sysco-lambda-code,S3Key=aws-lambda-compliance-0.0.1-jar-with-dependencies.jar ^
--role arn:aws:iam::885523507357:role/Sysco-LambdaEnforceTagComplianceRole ^
--handler com.sysco.aws.lambda.EC2InstanceTagComplianceChecker::handleEC2InstanceTagCompliance ^
--runtime java8 ^
--profile sysco-non-prod ^
--timeout 50 ^
--memory-size 512


aws lambda create-function ^
--region us-east-1 ^
--function-name CreateRoute53DNSRecord ^
--description "Create a DNS record in Route 53 when an instance is created." ^
--code S3Bucket=sysco-lambda-code,S3Key=aws-lambda-compliance-0.0.1-jar-with-dependencies.jar ^
--role arn:aws:iam::885523507357:role/Sysco-LambdaCreateRoute53DNSRecordRole ^
--handler com.sysco.aws.lambda.Route53DNSUpdater::handleRoute53DNSUpdates ^
--runtime java8 ^
--profile sysco-non-prod ^
--timeout 50 ^
--memory-size 512


aws lambda create-function ^
--region us-east-1 ^
--function-name DeleteRoute53DNSRecord ^
--description "Remove Route53 DNS record once EC2 instance is terminated." ^
--code S3Bucket=sysco-lambda-code,S3Key=aws-lambda-compliance-0.0.1-jar-with-dependencies.jar ^
--role arn:aws:iam::885523507357:role/Sysco-LambdaCreateRoute53DNSRecordRole ^
--handler com.sysco.aws.lambda.Route53DNSUpdater::handleRoute53DNSRemove ^
--runtime java8 ^
--profile sysco-non-prod ^
--timeout 50 ^
--memory-size 512


aws lambda create-function ^
--region us-east-1 ^
--function-name DetachedVolumeJanitor-TagDeleteOnStamp ^
--description "Marks detached volumes with a future deletion date, based on a configurable grace period." ^
--code S3Bucket=sysco-lambda-code,S3Key=aws-lambda-compliance-0.0.1-jar-with-dependencies.jar ^
--role arn:aws:iam::885523507357:role/Sysco-LambdaDetachedVolumeJanitor-TagDeleteOnStampRole ^
--handler com.sysco.aws.lambda.DetachedVolumeJanitor::handleDetachedVolumeScheduleDeleteStamp ^
--runtime java8 ^
--profile sysco-non-prod ^
--timeout 120 ^
--memory-size 512


aws lambda create-function ^
--region us-east-1 ^
--function-name DetachedVolumeJanitor-NotifyAndDelete ^
--description "Notify's upcoming volume deletions via SNS and deletes volumes tagged for deletion." ^
--code S3Bucket=sysco-lambda-code,S3Key=aws-lambda-compliance-0.0.1-jar-with-dependencies.jar ^
--role arn:aws:iam::885523507357:role/Sysco-LambdaDetachedVolumeJanitor-NotifyAndDeleteRole ^
--handler com.sysco.aws.lambda.DetachedVolumeJanitor::handleDetachedVolumeNotifyAndDelete ^
--runtime java8 ^
--profile sysco-non-prod ^
--timeout 120 ^
--memory-size 512

