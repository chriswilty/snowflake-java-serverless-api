package com.example.snowflake.citibike;

import com.fasterxml.jackson.annotation.JsonProperty;

public class EmptyResult {

	private final String result;
	private final long time_ms;

	public EmptyResult() {
		this.result = "Nothing to see here";
		this.time_ms = 0;
	}

	public String getResult() {
		return this.result;
	}

	@JsonProperty("time_ms")
	public long getTimeMs() {
		return this.time_ms;
	}
}
