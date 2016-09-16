echo "MVN: Packaging jar with dependencies ..."
call mvn clean package

echo "AWS: Copy jar to S3 bucket ..."
call aws s3 cp .\target\aws-lambda-compliance-0.0.1-jar-with-dependencies.jar s3://sysco-lambda-code --profile sysco-non-prod

echo "AWS: Deploying code to lambda from S3 ..."
call aws lambda update-function-code --function-name %1 --s3-bucket sysco-lambda-code --s3-key aws-lambda-compliance-0.0.1-jar-with-dependencies.jar --profile sysco-non-prod
