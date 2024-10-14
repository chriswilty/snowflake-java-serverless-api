import { HttpApi, HttpMethod } from 'aws-cdk-lib/aws-apigatewayv2';
import { HttpLambdaIntegration } from 'aws-cdk-lib/aws-apigatewayv2-integrations';
import { Code, Function, LayerVersion, Runtime, SnapStartConf } from 'aws-cdk-lib/aws-lambda';
import { RetentionDays } from 'aws-cdk-lib/aws-logs';
import { AssetHashType, BundlingOutput, Duration, ILocalBundling, Stack, StackProps, } from 'aws-cdk-lib/core';
import { Construct } from 'constructs';
import { execSync } from 'node:child_process';
import { createHash, Hash } from 'node:crypto';
import { readdirSync, statSync } from 'node:fs';
import { homedir } from 'node:os';
import { join, normalize } from 'node:path';
import { sync as md5Sync } from 'md5-file';

export class ApiStack extends Stack {
	constructor(scope: Construct, id: string, props?: StackProps) {
		super(scope, id, props);

		const { SNOWFLAKE_ACCOUNT, SNOWFLAKE_PK } = process.env;
		if (!SNOWFLAKE_ACCOUNT) throw new Error('Missing env var: SNOWFLAKE_ACCOUNT');
		if (!SNOWFLAKE_PK) throw new Error('Missing env var: SNOWFLAKE_PK');

		const tryLocalBuild = (this.node.tryGetContext('buildlocal') as string) === 'true';
		const timeoutSeconds = Number.parseInt(this.node.tryGetContext('timeout') as string) || 20;

		const local: ILocalBundling | undefined = tryLocalBuild ? {
			tryBundle(outDir: string): boolean {
				console.log('Bundling locally ...');
				try {
					execSync([
						`cd ${join(__dirname, '../lambdas/snowflake-citibike-api')}`,
						`${normalize('../mvnw')} clean package`,
						`cp ${normalize('target/citibike-api.jar')} ${outDir}`
					].join(' && '));
					return true;
				} catch (err: unknown) {
					console.log('Fail :(');
					console.error(err);
					return false;
				}
			}
		} : undefined;

		const arrowWorkaroundLayer = new LayerVersion(this, 'allow-nio-reflection', {
			description: 'Allows reflection of NIO package, needed by Apache Arrow',
			compatibleRuntimes: [Runtime.JAVA_17, Runtime.JAVA_21],
			code: Code.fromAsset(join(__dirname, '../lambdas/layers'), {
				bundling: {
					image: Runtime.JAVA_21.bundlingImage,
					user: 'root',
					entrypoint: ['/bin/sh', '-c'],
					command: ['cp -r allow-nio-reflection /asset-output/'],
				},
			}),
		});

		const citibikeApiFn = new Function(this, 'citibike-api', {
			functionName: 'citibike-api',
			handler: 'com.example.snowflake.citibike.Handler',
			runtime: Runtime.JAVA_21,
			snapStart: SnapStartConf.ON_PUBLISHED_VERSIONS,
			// You would never want
			timeout: Duration.seconds(timeoutSeconds),
			environment: {
				AWS_LAMBDA_EXEC_WRAPPER: '/opt/allow-nio-reflection/wrapper.sh',
				SNOWFLAKE_ACCOUNT,
				SNOWFLAKE_PK, // For real/remote deployments we would use SSM Parameter Store
				SNOWFLAKE_USER: 'DATA_APPS_DEMO',
				SNOWFLAKE_WAREHOUSE: 'DATA_APPS_DEMO',
				SNOWFLAKE_DATABASE: 'DATA_APPS_DEMO',
				SNOWFLAKE_SCHEMA: 'DEMO',
			},
			layers: [arrowWorkaroundLayer],
			code: Code.fromAsset(join(__dirname, '../lambdas'), {
				assetHashType: AssetHashType.CUSTOM,
				assetHash: calculateHash(join(__dirname, '../lambdas/snowflake-citibike-api/src')),
				bundling: {
					image: Runtime.JAVA_21.bundlingImage,
					user: 'root',
					entrypoint: ['/bin/sh', '-c'],
					command: [
						'cd snowflake-citibike-api && ' +
						'../mvnw clean package && ' +
						'cp target/citibike-api.jar /asset-output/'
					],
					volumes: [{
						containerPath: '/root/.m2',
						hostPath: join(homedir(), '.m2'),
					}],
					outputType: BundlingOutput.ARCHIVED,
					local,
				},
			}),
			logRetention: RetentionDays.ONE_DAY,
		});

		// Then API Gateway and endpoint
		const httpApi = new HttpApi(this, 'snowflake-api');

		httpApi.addRoutes({
			path: '/citibike/{proxy+}',
			methods: [HttpMethod.GET],
			integration: new HttpLambdaIntegration('citibike-api-integration', citibikeApiFn),
		});
	}
}

const calculateHash = (() => {
	const hashDir = (path: string): Hash =>
		readdirSync(path).reduce(
			(hash, file) => {
				const filePath = join(path, file);
				const fileStats = statSync(filePath);
				const nextPart: string | Buffer | null =
					fileStats.isFile() ? md5Sync(filePath) :
						fileStats.isDirectory() ? hashDir(filePath).digest() :
							null;
				return nextPart ? hash.update(nextPart) : hash;
			},
			createHash('md5')
		);

	return (basePath: string) => hashDir(basePath).digest('hex');
})();
