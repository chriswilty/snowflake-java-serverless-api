package com.example.snowflake.citibike;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import java.security.PrivateKey;
import java.security.Security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.amazonaws.services.lambda.runtime.logging.LogLevel;

import com.fasterxml.jackson.databind.ObjectMapper;

public class Handler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

	private static final ObjectMapper objectMapper = new ObjectMapper();
	private static final Map<String, String> CONTENT_JSON = Map.of("Content-Type", "application/json");

	private static class Connector {
		private static Connection conn = null;

		private static class PrivateKeyReader {
			private static PrivateKey get(String key) throws Exception {
				Security.addProvider(new BouncyCastleProvider());
				PEMParser pemParser = new PEMParser(new StringReader(key));
				Object pemObject = pemParser.readObject();
				pemParser.close();

				PrivateKeyInfo pkInfo;
				if (pemObject instanceof PrivateKeyInfo) pkInfo = (PrivateKeyInfo) pemObject;
				else if (pemObject instanceof PEMKeyPair) pkInfo = ((PEMKeyPair) pemObject).getPrivateKeyInfo();
				else {
					throw new UnsupportedOperationException(
						"Unsupported PEM format: " + pemObject.getClass()
					);
				}

				return new JcaPEMKeyConverter()
					.setProvider(BouncyCastleProvider.PROVIDER_NAME)
					.getPrivateKey(pkInfo);
			}
		}

		static Connection getConnection() throws Exception {
			if (conn == null) {
				Map<String, String> env = System.getenv();
				Properties props = new Properties();
				props.put("CLIENT_SESSION_KEEP_ALIVE", true);
				props.put("account", env.get("SNOWFLAKE_ACCOUNT"));
				props.put("user", env.get("SNOWFLAKE_USER"));
				props.put("privateKey", PrivateKeyReader.get(env.get("SNOWFLAKE_PK")));
				props.put("warehouse", env.get("SNOWFLAKE_WAREHOUSE"));
				props.put("db", env.get("SNOWFLAKE_DATABASE"));
				props.put("schema", env.get("SNOWFLAKE_SCHEMA"));
				String url = "jdbc:snowflake://" + env.get("SNOWFLAKE_ACCOUNT") + ".snowflakecomputing.com/";
				return DriverManager.getConnection(url, props);
			}
			return conn;
		}
	}

	private static void logError(Exception e, LambdaLogger logger) {
		logger.log(e.toString());
		logger.log(
			Arrays.stream(e.getStackTrace()).map(StackTraceElement::toString).collect(Collectors.joining("\n")),
			LogLevel.ERROR
		);
	}

	private static APIGatewayV2HTTPResponse errorResponse(String message) {
		return APIGatewayV2HTTPResponse.builder()
			.withStatusCode(500)
			.withBody(message)
			.build();
	}

	private static <T> APIGatewayV2HTTPResponse jsonResponse(T input, LambdaLogger logger) {
		final String body;
		try {
			return APIGatewayV2HTTPResponse.builder()
				.withStatusCode(200)
				.withHeaders(CONTENT_JSON)
				.withBody(objectMapper.writeValueAsString(input))
				.build();
		} catch (Exception e) {
			logError(e, logger);
			return errorResponse(e.getMessage());
		}
	}

	@Override
	public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent input, Context context) {
		context.getLogger().log("Received request at %s\n".formatted(input.getRawPath()));

		String path = input.getPathParameters().get("proxy");
		Map<String, String> queryStringParameters = input.getQueryStringParameters();

		try {
			PreparedStatement stat;
			switch (path) {
			case "trips/monthly":
				stat = monthlyPreparedStatement(queryStringParameters, Connector.getConnection());
				break;
			case "trips/weekday":
				stat = dayOfWeekPreparedStatement(queryStringParameters, Connector.getConnection());
				break;
			case "trips/temperature":
				stat = temperaturePreparedStatement(queryStringParameters, Connector.getConnection());
				break;
			default:
				return jsonResponse(new EmptyResult(), context.getLogger());
			}

			long start_time = System.nanoTime();
			ResultSet rs = stat.executeQuery();
			long time_ms = (System.nanoTime() - start_time) / 1_000_000;
			ArrayList<Object[]> results = new ArrayList<Object[]>();
			while (rs.next()) {
				results.add(new Object[] { rs.getObject(1), rs.getObject(2) });
			}

			return jsonResponse(new DataResult(results, time_ms), context.getLogger());
		} catch (Exception e) {
			logError(e, context.getLogger());
			return errorResponse("Something went wrong, check logs/history in Snowflake");
		}
	}

	private PreparedStatement monthlyPreparedStatement(Map<String, String> queryStringParameters, Connection conn)
			throws Exception {
		PreparedStatement stat;
		if (queryStringParameters != null && queryStringParameters.get("start_range") != null
				&& queryStringParameters.get("end_range") != null) {
			String sql = "select COUNT(*) as trip_count, MONTHNAME(starttime) as month from demo.trips where starttime between ? and ? group by MONTH(starttime), MONTHNAME(starttime) order by MONTH(starttime);";
			stat = conn.prepareStatement(sql);
			stat.setString(1, queryStringParameters.get("start_range"));
			stat.setString(2, queryStringParameters.get("end_range"));
			return stat;
		}
		String sql = "select COUNT(*) as trip_count, MONTHNAME(starttime) as month from demo.trips group by MONTH(starttime), MONTHNAME(starttime) order by MONTH(starttime);";
		stat = conn.prepareStatement(sql);
		return stat;
	}

	private PreparedStatement dayOfWeekPreparedStatement(Map<String, String> queryStringParameters, Connection conn)
			throws Exception {
		PreparedStatement stat;
		if (queryStringParameters != null && queryStringParameters.get("start_range") != null
				&& queryStringParameters.get("end_range") != null) {
			String sql = "select COUNT(*) as trip_count, DAYNAME(starttime) as day_of_week from demo.trips where starttime between ? and ? group by DAYOFWEEK(starttime), DAYNAME(starttime) order by DAYOFWEEK(starttime);";
			stat = conn.prepareStatement(sql);
			stat.setString(1, queryStringParameters.get("start_range"));
			stat.setString(2, queryStringParameters.get("end_range"));
			return stat;
		}
		String sql = "select COUNT(*) as trip_count, DAYNAME(starttime) as day_of_week from demo.trips group by DAYOFWEEK(starttime), DAYNAME(starttime) order by DAYOFWEEK(starttime);";
		stat = conn.prepareStatement(sql);
		return stat;
	}

	private PreparedStatement temperaturePreparedStatement(Map<String, String> queryStringParameters, Connection conn)
			throws Exception {
		PreparedStatement stat;
		if (queryStringParameters != null && queryStringParameters.get("start_range") != null
				&& queryStringParameters.get("end_range") != null) {
			String sql = "with weather_trips as (select * from demo.trips t inner join demo.weather w on date_trunc(\"day\", t.starttime) = w.observation_date) select round(temp_avg_f, -1) as temp, count(*) as trip_count from weather_trips where starttime between ? and ? group by round(temp_avg_f, -1) order by round(temp_avg_f, -1) asc;";
			stat = conn.prepareStatement(sql);
			stat.setString(1, queryStringParameters.get("start_range"));
			stat.setString(2, queryStringParameters.get("end_range"));
			return stat;
		}
		String sql = "with weather_trips as (select * from demo.trips t inner join demo.weather w on date_trunc(\"day\", t.starttime) = w.observation_date) select round(temp_avg_f, -1) as temp, count(*) as trip_count from weather_trips group by round(temp_avg_f, -1) order by round(temp_avg_f, -1) asc;";
		stat = conn.prepareStatement(sql);
		return stat;
	}
}
