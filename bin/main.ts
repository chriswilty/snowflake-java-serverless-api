#!/usr/bin/env node
import 'source-map-support/register';
import { App } from 'aws-cdk-lib/core';
import { ApiStack } from '../lib/api-stack';

const app = new App();

new ApiStack(app, 'ApiStack', {
  /* Uncomment the next line to specialize this stack for the AWS Account
   * and Region that are implied by the current CLI configuration. */
  // env: { account: process.env.CDK_DEFAULT_ACCOUNT, region: process.env.CDK_DEFAULT_REGION },

  /* Uncomment the next line if you know exactly what Account and Region you
   * want to deploy the stack to. */
  // env: { account: '101010101010', region: 'eu-north-1' },
});
