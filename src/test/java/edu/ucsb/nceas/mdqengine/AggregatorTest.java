package edu.ucsb.nceas.mdqengine;

import static org.junit.Assert.*;

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.BeforeClass;
import org.junit.Test;

import edu.ucsb.nceas.mdqengine.model.Recommendation;
import edu.ucsb.nceas.mdqengine.model.RecommendationFactory;

public class AggregatorTest {
	
	protected Log log = LogFactory.getLog(this.getClass());
	
	private static Recommendation recommendation;
	
	@BeforeClass
	public static void init() {
		recommendation = RecommendationFactory.getMockRecommendation();
	}

	@Test
	public void testBatchEML() {
		String query = "formatId:\"eml://ecoinformatics.org/eml-2.1.1\"";

		Aggregator aggregator = new Aggregator();
		String tabularResult = null;
		try {
			tabularResult = aggregator.runBatch(query, recommendation);
			assertNotNull(tabularResult);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		log.debug("Tabular Batch Result: \n" + tabularResult);
		
	}

}
