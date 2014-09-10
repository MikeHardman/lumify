package io.lumify.opennlpDictionary;

import com.google.inject.Injector;
import io.lumify.core.config.HashMapConfigurationLoader;
import io.lumify.core.ingest.graphProperty.GraphPropertyWorkData;
import io.lumify.core.ingest.graphProperty.GraphPropertyWorkerPrepareData;
import io.lumify.core.ingest.graphProperty.TermMentionFilter;
import io.lumify.core.model.properties.LumifyProperties;
import io.lumify.core.model.termMention.TermMentionRepository;
import io.lumify.core.security.DirectVisibilityTranslator;
import io.lumify.core.security.VisibilityTranslator;
import io.lumify.core.user.User;
import io.lumify.opennlpDictionary.model.DictionaryEntryRepository;
import opennlp.tools.dictionary.Dictionary;
import opennlp.tools.namefind.DictionaryNameFinder;
import opennlp.tools.namefind.TokenNameFinder;
import opennlp.tools.util.StringList;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.securegraph.Direction;
import org.securegraph.Vertex;
import org.securegraph.Visibility;
import org.securegraph.inmemory.InMemoryAuthorizations;
import org.securegraph.inmemory.InMemoryGraph;
import org.securegraph.inmemory.InMemoryVertex;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.securegraph.util.IterableUtils.toList;

@RunWith(MockitoJUnitRunner.class)
public class OpenNLPDictionaryExtractorGraphPropertyWorkerTest {
    private static final String RESOURCE_CONFIG_DIR = "/fs/conf/opennlp";

    private OpenNLPDictionaryExtractorGraphPropertyWorker extractor;

    @Mock
    private User user;

    private String text = "This is a sentence that is going to tell you about a guy named "
            + "Bob Robertson who lives in Boston, MA and works for a company called Altamira Corporation";

    private InMemoryAuthorizations authorizations;

    @Mock
    private DictionaryEntryRepository dictionaryEntryRepository;

    private InMemoryGraph graph;
    private VisibilityTranslator visibilityTranslator = new DirectVisibilityTranslator();

    @Before
    public void setUp() throws Exception {
        final List<TokenNameFinder> finders = loadFinders();

        Map config = new HashMap();
        config.put(io.lumify.core.config.Configuration.ONTOLOGY_IRI_PERSON, "http://lumify.io/test#person");
        config.put(io.lumify.core.config.Configuration.ONTOLOGY_IRI_LOCATION, "http://lumify.io/test#location");
        config.put(io.lumify.core.config.Configuration.ONTOLOGY_IRI_ORGANIZATION, "http://lumify.io/test#organization");
        config.put(io.lumify.core.config.Configuration.ONTOLOGY_IRI_ARTIFACT_HAS_ENTITY, "http://lumify.io/test#artifactHasEntity");
        io.lumify.core.config.Configuration configuration = new HashMapConfigurationLoader(config).createConfiguration();

        graph = new InMemoryGraph();

        extractor = new OpenNLPDictionaryExtractorGraphPropertyWorker() {
            @Override
            protected List<TokenNameFinder> loadFinders() throws IOException {
                return finders;
            }
        };
        extractor.setConfiguration(configuration);
        extractor.setDictionaryEntryRepository(dictionaryEntryRepository);
        extractor.setVisibilityTranslator(visibilityTranslator);
        extractor.setGraph(graph);

        config.put(OpenNLPDictionaryExtractorGraphPropertyWorker.PATH_PREFIX_CONFIG, "file:///" + getClass().getResource(RESOURCE_CONFIG_DIR).getFile());
        FileSystem hdfsFileSystem = FileSystem.get(new Configuration());
        authorizations = new InMemoryAuthorizations(TermMentionRepository.VISIBILITY);
        Injector injector = null;
        List<TermMentionFilter> termMentionFilters = new ArrayList<TermMentionFilter>();
        GraphPropertyWorkerPrepareData workerPrepareData = new GraphPropertyWorkerPrepareData(config, termMentionFilters, hdfsFileSystem, user, authorizations, injector);
        extractor.prepare(workerPrepareData);
    }

    @Test
    public void testEntityExtraction() throws Exception {
        InMemoryVertex vertex = (InMemoryVertex) graph.prepareVertex("v1", new Visibility(""))
                .setProperty("text", "none", new Visibility(""))
                .save(new InMemoryAuthorizations());
        graph.flush();

        GraphPropertyWorkData workData = new GraphPropertyWorkData(vertex, vertex.getProperty("text"), null, null);
        extractor.execute(new ByteArrayInputStream(text.getBytes()), workData);

        List<Vertex> termMentions = toList(vertex.getVertices(Direction.OUT, LumifyProperties.TERM_MENTION_LABEL_HAS_TERM_MENTION, authorizations));

        assertEquals(3, termMentions.size());

        boolean found = false;
        for (Vertex term : termMentions) {
            String title = LumifyProperties.TITLE.getPropertyValue(term);
            if (title.equals("Bob Robertson")) {
                found = true;
                assertEquals(63, LumifyProperties.TERM_MENTION_START_OFFSET.getPropertyValue(term, 0));
                assertEquals(76, LumifyProperties.TERM_MENTION_END_OFFSET.getPropertyValue(term, 0));
                break;
            }
        }
        assertTrue("Expected name not found!", found);

        ArrayList<String> signs = new ArrayList<String>();
        for (Vertex term : termMentions) {
            String title = LumifyProperties.TITLE.getPropertyValue(term);
            signs.add(title);
        }

        assertTrue("Bob Robertson not found", signs.contains("Bob Robertson"));
        assertTrue("Altamira Corporation not found", signs.contains("Altamira Corporation"));
        assertTrue("Boston , MA not found", signs.contains("Boston , MA"));
    }

    private List<TokenNameFinder> loadFinders() {
        List<TokenNameFinder> finders = new ArrayList<TokenNameFinder>();
        Dictionary people = new Dictionary();
        people.put(new StringList("Bob Robertson".split(" ")));
        finders.add(new DictionaryNameFinder(people, "person"));

        Dictionary locations = new Dictionary();
        locations.put(new StringList("Boston , MA".split(" ")));
        finders.add(new DictionaryNameFinder(locations, "location"));

        Dictionary organizations = new Dictionary();
        organizations.put(new StringList("Altamira Corporation".split(" ")));
        finders.add(new DictionaryNameFinder(organizations, "organization"));

        return finders;
    }
}
