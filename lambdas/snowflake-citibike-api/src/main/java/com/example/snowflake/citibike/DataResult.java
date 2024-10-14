package com.example.snowflake.citibike;

import java.util.ArrayList;
import com.fasterxml.jackson.annotation.JsonProperty;

public class DataResult {

	private final ArrayList<Object[]> result;
	private final long time_ms;

	public DataResult(ArrayList<Object[]> result, long time_ms) {
		this.result = result;
		this.time_ms = time_ms;
	}

	public ArrayList<Object[]> getResult() {
		return this.result;
	}

	@JsonProperty("time_ms")
	public long getTimeMs() {
		return this.time_ms;
	}
}
