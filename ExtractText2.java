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

