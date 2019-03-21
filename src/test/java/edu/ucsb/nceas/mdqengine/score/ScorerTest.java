package edu.ucsb.nceas.mdqengine.score;

import edu.ucsb.nceas.mdqengine.MDQEngine;
import edu.ucsb.nceas.mdqengine.exception.MetadigException;
import edu.ucsb.nceas.mdqengine.model.Run;
import edu.ucsb.nceas.mdqengine.model.RunFactory;
import edu.ucsb.nceas.mdqengine.model.Suite;
import edu.ucsb.nceas.mdqengine.model.SuiteFactory;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.dataone.service.types.v2.SystemMetadata;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import static org.dataone.configuration.Settings.getConfiguration;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

@Ignore
public class ScorerTest {
	
	protected Log log = LogFactory.getLog(this.getClass());

	private String id = "doi:10.5063/AA/tao.1.1";

	private Suite suite = null;
	
	@Before
	public void setUpSuite() {
		suite = SuiteFactory.getMockSuite();
	}
	
	@Test
	public void testRunSuiteForId() throws MetadigException, IOException, ConfigurationException {
		MDQEngine mdqe = new MDQEngine();
		Run run = null;
		try {
			// retrieve the metadata content
			String cnURL = getConfiguration().getString("D1Client.CN_URL");
			log.error("CN URL: " + cnURL);
			String metadataURL = "https://cn.dataone.org/cn/v2/object/" + id;
			InputStream input = new URL(metadataURL).openStream();
			SystemMetadata sysMeta = null;
			// run the suite on it
			run = mdqe.runSuite(suite, input, null, sysMeta);
			run.setObjectIdentifier(id);
			
			log.debug("Run score: " + Scorer.getCompositeScore(run));
			
			assertEquals(1.0, Scorer.getCompositeScore(run), 0);
			
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			fail(e.getMessage());
		}

	}
	
	@Test
	public void testMixedRun() {
		
		Run run = RunFactory.getMockRun();
		
		double weightedScore = Scorer.getWeightedScore(run);
		log.debug("Weighted run score=" + weightedScore);
		
		double compositeScore = Scorer.getCompositeScore(run);
		log.debug("Composite run score=" + compositeScore);
		
	}

}
