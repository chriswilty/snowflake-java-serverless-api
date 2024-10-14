# A Simple API Powered by Snowflake

This repo is a result of reimplementing the
[Snowflake Custom API](https://quickstarts.snowflake.com/guide/build_a_custom_api_in_java_on_aws/index.html) tutorial
using CDK and SAM Local - just to show you can do it without needing an AWS or Serverless account.

Note that the original tutorial is broken in a few ways, including key pair authentication; this repo fixes those.

Technologies used:
[Snowflake](https://snowflake.com/),
[AWS API Gateway](https://aws.amazon.com/api-gateway/),
[AWS Lambda](https://aws.amazon.com/lambda/),
[AWS CDK](https://docs.aws.amazon.com/cdk/v2/guide/home.html)

Requirements: 
* Snowflake.com account ([1-month trial account is free](https://signup.snowflake.com/?utm_cta=quickstarts_))
* [Java 21](https://www.oracle.com/java/technologies/downloads/#java21)
* [Docker Desktop](https://www.docker.com/products/docker-desktop/)
* Citibike data loaded into Snowflake
* Snowflake user authorized to access citibike data with key pair authentication

For the last two of these, complete the first four steps of
[Snowflake QuickStart Data Tutorial](https://quickstarts.snowflake.com/guide/data_app/index.html)

## Preamble

This project demonstrates how to build and deploy a custom API powered by Snowflake. It uses a simple Java API service
running on AWS Lambda, fronted by API Gateway. Connectivity to Snowflake uses key pair authentication.

## Configuration

### AWS

If you only wish to run the services locally using SAM CLI, and do not intend to deploy the resources to an AWS account,
you'll still need a lightweight profile in your AWS config. It can look something like this:

```
[default]
region = eu-north-1
output = json
```

Otherwise, you can use your remote AWS account, though you will then need to ensure you've
[CDK Bootstrapped](https://docs.aws.amazon.com/cdk/v2/guide/ref-cli-cmd-bootstrap.html) your remote environment.

### Snowflake

Copy `.env.example`, rename as `.env` and then modify its content with your Snowflake ID and Private Key.
The `.env` file is git-ignored and should never be committed!

## Run locally using SAM CLI

```shell
# Synthesize the CloudFormation templates
npm run synth:local

# Spin up API Gateway
npm run sam:api
```

Then test the [endpoints described below](#endpoints) using this base URL:

```shell
curl http://localhost:3000/citibike/
```

Note that even though I have configured a long lambda timeout for running locally, there is still a chance the function
could time out on first call, before the response from Snowflake is received. However, much of the work that SAM Local
and Snowflake need to do results in a warm lambda function and populated caches, so subsequent calls should complete
much quicker without timing out.

## Deploy to remote AWS environment

```shell
# Synthesize the CloudFormation templates
npm run synth

# Deploy to AWS environment (as configured in default profile)
npm run deploy
```

Once deployment succeeds, copy the Gateway URL output from the deploy command, and use it to test the
[endpoints described below](#endpoints), e.g.

```shell
curl https://[api-id].execute-api.[region].amazonaws.com/citibike/monthly
```

Note that the CDK commands above assume your target environment is defined as
[default profile in your AWS config](https://docs.aws.amazon.com/cdk/v2/guide/cli.html#cli-environment).
Alternatively, you can define your environment in a named profile and pass that using the `--profile` flag:

```shell
# Use profile "snowflake-demo"
npm run synth -- --profile snowflake-demo
```

## Endpoints

- `GET /citibike/trips/monthly` - returns trip count for each month of the year
- `GET /citibike/trips/weekday` - returns trip count for each weekday
- `GET /citibike/trips/temperature` - returns trip count grouped by trip temperature

All other endpoints will return a 404 Not Found response.
