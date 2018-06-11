/**
 * TODO:
 * 1. Create system to post result file to dataset without user/pass, secretkey causes permission problems.
 * 2. Create system for persistence users, datasets etc across relaunch of containers
 *      done by giving mongo a space in the filesystem in the docker-compose.yml file
 *      probably best to install clowder as a non-docker app.
 * 3. Create system to resubmit file for extraction.
 * 4. Fixed error with extracting from mp3 file.
 * 5. Add extractors for additional services and frameworks
 * 6. Post phrase number in clowder to give indication of progress.
 * 7. Create system to get data and model files from .zip file
 * 8. Add extractors for SRT to other caption file formats
 * 9. Create git repository
 * 10. Configure Clowder to send messages to ExtractText when mp4 files are uploaded
 *
 *
 * use this to print methodname to log file when debugging 
 *      class Local {}
 *      String methodName = Local.class.getEnclosingMethod().getName();
 *      logger.info(methodName + " called");
 */

package edu.illinois.ncsa.medici.extractor;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.BufferedInputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.HttpURLConnection;
import java.net.URLConnection;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.lang.StringBuilder;
import java.util.Timer;
import java.util.TimerTask;

import javax.xml.bind.DatatypeConverter;

import org.json.*;
import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;

import edu.cmu.sphinx.api.Configuration;
import edu.cmu.sphinx.api.SpeechResult;
import edu.cmu.sphinx.api.StreamSpeechRecognizer;
import edu.cmu.sphinx.result.WordResult;
import edu.cmu.sphinx.result.Result;
import edu.cmu.sphinx.util.TimeFrame;

public class SpeechRecognizer {
	private Log logger = LogFactory.getLog(SpeechRecognizer.class);

	private static Properties props;
	// address of rabbitmq instance
	private static String rabbitmqHost; //  = "localhost"
	private static String rabbitmqURI; // = ""

	// username and password for connecting to rabbitmq
	private static String rabbitmqUsername; // = null
	private static String rabbitmqPassword; // = null

	// name showing in rabbitmq queue list
	private static String exchange; // = "medici"
	private static String extractorName;

	// array storing message types for acceptable files
	private static String[] messageTypes; // ="*.file.audio.#","*.file.video.#"

	// paths for CMUSphinx model files
	private static String acousticModelPath;
	private static String dictionaryPath;
	private static String languageModelPath;

	// Date format used in making srt files
	private static DateFormat dateFormat;

	// Object mapper for reading and writing json
	private static ObjectMapper mapper;

	// Username and password for posting file to dataset
	private static String postFileUsername;
	private static String postFilePassword;
	private static String postBaseFileName;
	private static String postFileNameExtension;


	/** 
	 * Initializing all the static variables for their use in the main
	 * Loads the file named "config.properties" which contains key-val pairs
	 * and loads the vals to their respective variable
	 */
	private static void initialize() throws Exception {
		props = new Properties();
		FileInputStream inStream;
		try {
			inStream = new FileInputStream("config.properties");
			props.load(inStream);
			inStream.close();
		} catch(FileNotFoundException e) {
			System.out.println("File was not found!");
			e.printStackTrace();
		} catch(IOException e) {
			System.out.println("IO Exception thrown");
			e.printStackTrace();
		}
		rabbitmqHost = props.getProperty("rabbitmqHost");
		rabbitmqURI = props.getProperty("rabbitmqURI");
		extractorName = props.getProperty("extractorName");
		String messageType = props.getProperty("messageTypes");
		messageTypes = messageType.split(",");
		exchange = props.getProperty("exchange");
		rabbitmqUsername = props.getProperty("rabbitmqUsername");
		rabbitmqPassword = props.getProperty("rabbitmqPassword");
		acousticModelPath = props.getProperty("acousticModelPath");
		dictionaryPath = props.getProperty("dictionaryPath");
		languageModelPath = props.getProperty("languageModelPath");
		postFileUsername = props.getProperty("postFileUsername");
		postFilePassword = props.getProperty("postFilePassword");
		postBaseFileName = "";
		dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
		mapper = new ObjectMapper();
	}

	public void onMesage(Channel channel, long tag, AMQP.BasicProperties header, String body) {
		File inputFile = null;
		String fileId = "";
		String secretKey = "";
		String datasetId = "";

		try {
			@SuppressWarnings("unchecked")
			Map<String, Object> jbody = mapper.readValue(body, Map.class);
			System.out.println("JBODY:\n" + jbody + "\n");
			String host = jbody.get("host").toString();
			fileId = jbody.get("id").toString();
			secretKey = jbody.get("secretKey").toString();
			datasetId = jbody.get("datasetId").toString();
			postBaseFileName = jbody.get("filename").toString();
			String[] base_ext = postBaseFileName.split("\\.(?=[^\\.]+$");
			postBaseFileName = base_ext[0];
			postFileNameExtension = base_ext[1];
			String intermediateFileId = jbody.get("intermediateId").toString();
			if (!host.endsWith("/")) {
				host += "/";
			}

			// Download file
			statusUpdate(channel, header, fileId, "Started downloading file: " 
				+ postBaseFileName + "." + postFileNameExtension);
			inputFile = downloadFile(channel, header, host, secretKey, fileId, intermediateFileId);

			// Process file
			statusUpdate(channel, header, fileId, "Started processing file: " 
				+ postBaseFileName + "." + postFileNameExtension);
			processFile(channel, header, host, secretKey, fileId, intermediateFileId, inputFile);

			// Send rabbit that we are done
			channel.basicAck(tag, false);
		} catch (Throwable arg) {
			logger.error("Error processing file", arg);
			try {
				statusUpdate(channel, header, fileId, "Error processing file: " + arg.getMessage());
			} catch (IOException e) {
				logger.warn("Could not sent status update.", e);
			}
		} finally {
			try {
				statusUpdate(channel, header, fileId, "Done");
			} catch (IOException e) {
				logger.warn("Could not sent status update.", e);
			}
			if (inputFile != null) {
				inputFile.delete();
			}
		}
	}

	/**
     * Send status update to rabbitmq 
     * @param channel rabbitmq channel to send updates over
     * @param header header of incoming message, used for sending responses
     * @param fileId the id of file to be processed
     * @param status the actual message to send back.
     * @throws IOException if anything goes wrong.
     */
	private void statusUpdate(Channel channel, AMQP.BasicProperties header, String fileId, String status) 
	throws IOException {
		Map<String, Object> statusReport = new HashMap<String, Object>();
		statusReport.put("file_id", fileId);
		statusReport.put("extractor_id", extractorName);
		statusReport.put("status", status);
		statusReport.put("start", dateFormat.format(new Date()));

		AMQP.BasicProperties props = new AMQP.BasicProperties.Builder().correlationId(header.getCorrelationId()).build();
		channel.basicPublish("", header.getReplyTo(), props, mapper.writeValueAsBytes(statusReport));
	}

    /**
     * Download File from Medici 
     * @param channel rabbitmq channel to send messages over
     * @param header header of incoming message, used for sending responses
     * @param host the remote host to connect to
     * @param key the secret key to access clowder
     * @param fileId the id of file to be processed
     * @param intermediateFileId actual id of the raw file data to process. return the actual file downloaded from the server.
     * @throws IOException if anything goes wrong.
     * @throws UnsupportedOperationsException if the file is not convertable to wav.
     */
	private File downloadFile(Channel channel, AMQP.BasicProperties header, String host, 
		String key, String fileId, String intermediateFileId)
	throws IOException, JSONException, InterruptedException, UnsupportedOperationException {
		URL source = new URL(host + "api/files/" + intermediateFileId + "?key="
			+ key);
		URL metadata = new URL(host + "api/files/" + intermediateFileId + "/metadata");
		String fileType = "";
		/*
			Code for getting file type
		*/

		HttpURLConnection connection = (HttpURLConnection) metadata.openConnection();
		if (connection.getResponseCode() != 200) {
			throw new IOException(connection.getResponseMessage());
		}
		BufferedReader readMetadata = new BufferedReader(new InputStreamReader(connection.getInputStream()));
		String metadataText = readAll(readMetadata);
		JSONObject json = new JSONObject(metadataText);
		fileType = json.getString("filename");

		File tmpFile = File.createTempFile("medici", "." + fileType);
		tmpFile.deleteOnExit();
		FileUtils.copyURLToFile(source,tmpFile);

		String outputFileName = "/tmp/output.wav"
		// Convert File to Sphinx usable format using ffmpeg cmd line tool
		String convertCmd = "/root/bin/ffmpeg -i " + tmpFile + " -acodec pcm_s161e -ar 1600 " + outputFileName;
		try {
			Process convertFile = Runtime.getRuntime().exec(convertCmd);
			outputFile = new File(outputFileName);
		} catch (Exception e) {
			System.out.print("File not convertable");
			throw UnsupportedOperationException("File not convertable");
		}
		return outputFile;
		
	}

	private void processFile(Channel channel, AMQP.BasicProperties header,
		String host, String key, String fileId, String datasetId,
		String intermediateFileId, File inputFile) throws IOException, InterruptedException {
		Configuration config = new Configuration();

		// Set up the configuration to be used by sphinx
		configuration.setAcousticModelPath("resource:"+acousticModelPath);
		configuration.setDictionaryPath("resource:"+dictionaryPath);
		configuration.setLanguageModelPath("resource:"+languageModelPath);

		StreamSpeechRecognizer recognizer = new StreamSpeechRecognizer(configuration);
		String fileName = "file:" + inputFile;
		recognizer.startRecognition(new URL(fileName).openStream());
		SpeechResult result;
		String postSRTFilenameString = postBaseFileName + ".srt";
		File srt = new File(postSRTFilenameString);
		String absolutePath = srt.getAbsolutePath();
		if (srt.exists())
			srt.delete();
		srt.createNewFile();
		BufferedWriter out = new BufferedWriter(new FileWriter(postSRTFilenameString));
		Map<String, Object> metadata = new HashMap<String, Object>();
		int numPhrases = 0;
		while((result = recognizer.getResult() != null)) {
			numPhrases++;
			Result script = result.getResult();

			// Generating SRT file from the result
			out.write(numPhrases+"\r\n");
			List<WordResult> wrList = script.getTimedBestResult(false);
			if (!wrList.isEmpty()) {

				// Start time of dialogue
				TimeFrame startFrame = wrList.getTimeFrame();
				long milStart = startFrame.getStart();
				long secStart = TimeUnit.MILLISECONDS.toSeconds(milStart);
				long minStart = TimeUnit.MILLISECONDS.toMinutes(milStart);
				long hrStart = TimeUnit.MILLISECONDS.toHours(milStart);
				milStart -= TimeUnit.MILLISECONDS.toMillis(secStart);
				// End time of dialogue
				TimeFrame endFrame = wrList.get(wrList.size()-1).getTimeFrame();
				long milEnd = startEnd.getStart();
				long secEnd = TimeUnit.MILLISECONDS.toSeconds(milEnd);
				long minEnd = TimeUnit.MILLISECONDS.toMinutes(milEnd);
				long hrEnd = TimeUnit.MILLISECONDS.toHours(milEnd);
				milEnd -= TimeUnit.MILLISECONDS.toMillis(secEnd);
				out.write(String.format("%02d:%02d:02d,%d", hrStart, minStart, secStart, milStart));
				out.write(" --> ");
				out.write(String.format("%02d:%02d:02d,%d\r\n", hrEnd, minEnd, secEnd, milEnd));
				for (int i = 0; i < wrList.size(); i++)
					out.write(wrList.get(i).getWord() + " ");
				out.write("\r\n\r\n");
				Sting phraseName = "phrase" + numPhrases;
				metadata.put(phraseName, result.getNbest);
			}
		}
		out.close();
		recognizer.stopRecognition();
		System.out.println("Finished Recognition");

		// Inserting Captions into mp4 files
		if(postFileNameExtension.equals(mp4)) {
			statusUpdate(channel, header, fileId, "Inserting Captions");
			String outputFileName = insertCaptions(postSRTFilenameString, ""+inputFile);
			System.out.println("Finished Processing\nMetadata: " + metadata);
			postMetaData(host, key, fileId, metadata);
			postFile(host, fileId, datasetId, outputFileName);
		} else {
			System.out.println("Finished Processing\nMetadata: " + metadata);
			postMetaData(host, key, fileId, metadata);
			postFile(host, fileId, datasetId, postSRTFilenameString);
		}
		
	}
}
