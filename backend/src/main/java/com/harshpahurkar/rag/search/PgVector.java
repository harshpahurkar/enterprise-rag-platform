package com.harshpahurkar.rag.search;

/** pgvector text literal for a float[] embedding, bound as a string and cast with {@code ::vector}. */
public final class PgVector {

	private PgVector() {
	}

	public static String literal(float[] vector) {
		StringBuilder sb = new StringBuilder(vector.length * 12).append('[');
		for (int i = 0; i < vector.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(vector[i]);
		}
		return sb.append(']').toString();
	}

}
