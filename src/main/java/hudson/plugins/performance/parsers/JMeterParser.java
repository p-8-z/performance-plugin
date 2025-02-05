package hudson.plugins.performance.parsers;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Date;

import javax.xml.parsers.SAXParserFactory;

import org.kohsuke.stapler.DataBoundConstructor;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import hudson.Extension;
import hudson.plugins.performance.data.HttpSample;
import hudson.plugins.performance.descriptors.PerformanceReportParserDescriptor;
import hudson.plugins.performance.reports.PerformanceReport;

/**
 * Parser for JMeter.
 *
 * @author Kohsuke Kawaguchi
 */
public class JMeterParser extends AbstractParser {

    @Extension
    public static class DescriptorImpl extends PerformanceReportParserDescriptor {
        @Override
        public String getDisplayName() {
            return "JMeter";
        }
    }


    public JMeterParser(String glob, String percentiles) {
        super(glob, percentiles, PerformanceReport.INCLUDE_ALL);
    }
    
    @DataBoundConstructor
    public JMeterParser(String glob, String percentiles, String filterRegex) {
        super(glob, percentiles, filterRegex);
    }

    @Override
    public String getDefaultGlobPattern() {
        return "**/*.jtl";
    }

    PerformanceReport parse(File reportFile) throws Exception {
        // JMeter stores either CSV or XML in .JTL files.
        final boolean isXml = isXmlFile(reportFile);

        if (isXml) {
            return parseXml(reportFile);
        } else {
            return parseCsv(reportFile);
        }
    }

    /**
     * Utility method that checks if the provided file has XML content.
     * <p>
     * This implementation looks for the first non-empty file. If an XML prolog appears there, this method returns <code>true</code>, otherwise <code>false</code> is returned.
     *
     * @param file File from which the content is to e analyzed. Cannot be null.
     * @return <code>true</code> if the file content has been determined to be XML, otherwise <code>false</code>.
     */
    public static boolean isXmlFile(File file) throws IOException {
        try (FileReader fr = new FileReader(file);
                BufferedReader reader = new BufferedReader(fr)) {
            String line;
            boolean isXml = false;
            while ((line = reader.readLine()) != null) {
                if (line.trim().length() == 0) {
                    continue; // skip empty lines.
                } 
                if (line.toLowerCase().trim().startsWith("<?xml ")) {
                    isXml = true;
                }
                break;
            }
            return isXml;
        }
    }

    /**
     * A delegate for {@link #parse(File)} that can process XML data.
     */
    PerformanceReport parseXml(File reportFile) throws Exception {
        final SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setValidating(false);
        factory.setNamespaceAware(false);

        final PerformanceReport report = createPerformanceReport();
        report.setExcludeResponseTime(excludeResponseTime);
        report.setShowTrendGraphs(showTrendGraphs);
        report.setReportFileName(reportFile.getName());

        factory.newSAXParser().parse(reportFile, new DefaultHandler() {
            HttpSample currentSample;
            int counter = 0;

            /**
             * Performance XML log format is in http://jakarta.apache.org/jmeter/usermanual/listeners.html
             *
             * There are two different tags which delimit jmeter samples:
             * - httpSample for http samples
             * - sample for non http samples
             *
             * There are also two different XML formats which we have to handle:
             * v2.0 = "label", "timeStamp", "time", "success"
             * v2.1 = "lb", "ts", "t", "s"
             */
            @Override
            public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
                if (!"httpSample".equalsIgnoreCase(qName) && !"sample".equalsIgnoreCase(qName)) {
                    return;
                }

                final HttpSample sample = new HttpSample();

                final String dateValue;
                if (attributes.getValue("ts") != null) {
                    dateValue = attributes.getValue("ts");
                } else {
                    dateValue = attributes.getValue("timeStamp");
                }
                sample.setDate(new Date(Long.valueOf(dateValue)));

                final String durationValue;
                if (attributes.getValue("t") != null) {
                    durationValue = attributes.getValue("t");
                } else {
                    durationValue = attributes.getValue("time");
                }
                sample.setDuration(Long.valueOf(durationValue));

                final String successfulValue;
                if (attributes.getValue("s") != null) {
                    successfulValue = attributes.getValue("s");
                } else {
                    successfulValue = attributes.getValue("success");
                }
                sample.setSuccessful(Boolean.parseBoolean(successfulValue));

                final String uriValue;
                if (attributes.getValue("lb") != null) {
                    uriValue = attributes.getValue("lb");
                } else {
                    uriValue = attributes.getValue("label");
                }
                sample.setUri(uriValue);

                final String httpCodeValue;
                if (attributes.getValue("rc") != null && attributes.getValue("rc").length() <= 3) {
                    httpCodeValue = attributes.getValue("rc");
                } else {
                    httpCodeValue = "0";
                }
                sample.setHttpCode(httpCodeValue);

                final String sizeInKbValue;
                if (attributes.getValue("by") != null) {
                    sizeInKbValue = attributes.getValue("by");
                } else {
                    sizeInKbValue = "0";
                }
                sample.setSizeInKb(Double.valueOf(sizeInKbValue) / 1024d);

                if (counter == 0) {
                    currentSample = sample;
                }
                counter++;
            }

            @Override
            public void endElement(String uri, String localName, String qName) {
                if ("httpSample".equalsIgnoreCase(qName) || "sample".equalsIgnoreCase(qName)) {
                    if (counter == 1) {
                        report.addSample(currentSample);
                    }
                    counter--;
                }
            }
        });

        return report;
    }

    /**
     * A delegate for {@link #parse(File)} that can process CSV data.
     */
    PerformanceReport parseCsv(File reportFile) throws Exception {
        final JMeterCsvParser delegate = new JMeterCsvParser(this.glob, this.percentiles, this.filterRegex);
        return delegate.parse(reportFile);
    }

}
