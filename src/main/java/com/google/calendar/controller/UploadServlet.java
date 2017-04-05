
package com.google.calendar.controller;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.CalendarList;
import com.google.api.services.calendar.model.CalendarListEntry;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.Events;
import com.google.calendar.constant.CalendarConstant;
import com.google.calendar.csv.reader.CSVReader;
import com.google.calendar.excel.output.ExcelService;
import com.google.calendar.exception.ExcelFormatException;
import com.google.calendar.factory.ServiceFactory;
import com.google.calendar.service.CalendarService;

/**
 * Controller class to Handle incoming request with CSV file Will Read CSV file
 * and generate Excel
 * 
 * @author DAMCO
 *
 */
public class UploadServlet extends HttpServlet {

	/**
	 * default serial version
	 */
	private static final long serialVersionUID = 1L;
	
	
	public final Logger logger = Logger.getLogger(UploadServlet.class);

	/**
	 * Servlet post method to handle incoming post request
	 * 
	 * (non-Javadoc)
	 * 
	 * @see javax.servlet.http.HttpServlet#doPost(javax.servlet.http.HttpServletRequest,
	 *      javax.servlet.http.HttpServletResponse)
	 */
	@Override
	public void doPost(final HttpServletRequest request, final HttpServletResponse response)
			throws ServletException, IOException {

		response.setContentType(CalendarConstant.CONTENT_TYPE);
		CSVReader csvReader = (CSVReader) ServiceFactory.getInstance(CSVReader.class);
		Map<String, String> inputMap = csvReader.readCSV(request, response);

		List<String> calendarName = Arrays.asList(inputMap.get("CALENDAR").split(CalendarConstant.COMMA_SPLITTER));
		String templatePath = inputMap.get(CalendarConstant.TEMPLATE) != null ? inputMap.get(CalendarConstant.TEMPLATE)
				: CalendarConstant.TEMPLATE_FILE_NAME;
		String resultName = inputMap.get(CalendarConstant.OUTFILE) != null ? inputMap.get(CalendarConstant.OUTFILE)
				: CalendarConstant.RESULT_FILE_NAME;
		String inOutPath = inputMap.get(CalendarConstant.INOUTMAP) != null ? inputMap.get(CalendarConstant.INOUTMAP)
				: CalendarConstant.CONFIGURATION_FILE_NAME;

		// optional need to check for null at the time of logic
		List<String> projectName = inputMap.get(CalendarConstant.PROJECT) != null
				? Arrays.asList(inputMap.get(CalendarConstant.PROJECT).split(CalendarConstant.COMMA_SPLITTER)) :  new ArrayList<>();
		List<String> clientName = inputMap.get(CalendarConstant.CLIENT) != null
				? Arrays.asList(inputMap.get(CalendarConstant.CLIENT).split(CalendarConstant.COMMA_SPLITTER))
				: new ArrayList<>();

				InputStream inputStream = null;
		// created Date format for date 201703010000
		SimpleDateFormat dateFormat = new SimpleDateFormat(CalendarConstant.DATE_FORMAT);
		DateTime from = null;
		DateTime to = null;
		try {
			Date fromDate = inputMap.get(CalendarConstant.FROM) != null
					? dateFormat.parse(inputMap.get(CalendarConstant.FROM)) : new Date();
			@SuppressWarnings("deprecation")
			Date toDate = inputMap.get(CalendarConstant.TO) != null
					? dateFormat.parse(inputMap.get(CalendarConstant.TO)) : new Date(fromDate.getYear(), 12, 31);

			from = new DateTime(fromDate);
			to = new DateTime(toDate);

			// Build a new authorized API client service.
			// Note: Do not confuse this class with the
			// com.google.api.services.calendar.model.Calendar class.
			CalendarService calendarService = (CalendarService) ServiceFactory.getInstance(CalendarService.class);
			Calendar service = calendarService.getCalendarService();

			Map<String, List<DateTime>> excelData = new HashMap<>();
			String userName = "";
			String pageToken = null;

			do {
				CalendarList calendarList = service.calendarList().list().setPageToken(pageToken).execute();
				List<CalendarListEntry> listItems = calendarList.getItems();

				for (CalendarListEntry calendarListEntry : listItems) {
					if (calendarName.contains(calendarListEntry.getSummary())) {
						final Events events = service.events().list(calendarListEntry.getId()).setMaxResults(100)
								.setTimeMin(from).setOrderBy(CalendarConstant.START_TIME).setTimeMax(to)
								.setSingleEvents(true).execute();
						final List<Event> items = events.getItems();
						if (items.isEmpty()) {

						} else {
							userName = items.get(0).getCreator().getDisplayName();
							for (final Event event : items) {
								if ((clientName.contains(getProjecAndClienttName(event.getSummary()).get("CLI").trim()) || clientName.isEmpty() )
										&&( projectName
												.contains(getProjecAndClienttName(event.getSummary()).get("PRJ").trim())) ||  projectName.isEmpty() ) {
									DateTime start = event.getStart().getDateTime();
									DateTime end = event.getEnd().getDateTime();
									if (start == null) {
										start = event.getStart().getDate();
									}
									// put start event date at index 0 and end
									// date
									// at index 1
									List<DateTime> dateList = new LinkedList<>();
									dateList.add(start);
									dateList.add(end);
									excelData.put(event.getSummary(), dateList);
									
								}
							}
						}
					}
				}
				pageToken = calendarList.getNextPageToken();
			} while (pageToken != null);

			List<Date> dateList = new LinkedList<>();
			dateList.add(fromDate);
			dateList.add(toDate);
			ExcelService excelService = (ExcelService) ServiceFactory.getInstance(ExcelService.class);
			excelService.generateExcel(userName, projectName, clientName, templatePath, inOutPath, excelData, dateList);

			File file = new File(CalendarConstant.DESTINATION_FILE_PATH);
			inputStream = new FileInputStream(file);

			response.setHeader(CalendarConstant.CONTENT_HEADER, "attachment; filename=" + resultName);
			OutputStream outstream = response.getOutputStream();
			IOUtils.copyLarge(inputStream, outstream);

		} catch (ParseException e) {
			logger.error(CalendarConstant.LOGGER_DEFAULT_MESSAGE , e);
			try {
				request.setAttribute(CalendarConstant.ERROR_MESSAGE, CalendarConstant.ERROR_IN_PARSING_DATE);
				request.getRequestDispatcher(CalendarConstant.HOME_PAGE).forward(request, response);
			} catch (ServletException | IOException e1) {				
				request.setAttribute(CalendarConstant.ERROR_MESSAGE, CalendarConstant.ERROR_IN_LOADING);
			}
		} catch (ExcelFormatException e) {
			logger.error(CalendarConstant.LOGGER_DEFAULT_MESSAGE , e);
			try {
				request.setAttribute(CalendarConstant.ERROR_MESSAGE,  CalendarConstant.ERROR_IN_READING_EXCEL);
				request.getRequestDispatcher(CalendarConstant.HOME_PAGE).forward(request, response);
			} catch (ServletException | IOException e1) {
				logger.error(CalendarConstant.LOGGER_DEFAULT_MESSAGE , e1);
				request.setAttribute(CalendarConstant.ERROR_MESSAGE, CalendarConstant.ERROR_IN_LOADING);
			}

		} catch (Exception e) {
			logger.error(CalendarConstant.LOGGER_DEFAULT_MESSAGE , e);
			
			try {
				request.setAttribute(CalendarConstant.ERROR_MESSAGE,  CalendarConstant.ERROR_IN_SCV_VALIDATION);
				request.getRequestDispatcher(CalendarConstant.HOME_PAGE).forward(request, response);
			} catch (ServletException | IOException e1) {
				logger.error(CalendarConstant.LOGGER_DEFAULT_MESSAGE , e1);
				request.setAttribute(CalendarConstant.ERROR_MESSAGE, CalendarConstant.ERROR_IN_LOADING);
			}

		}
		finally {
			if(inputStream != null)
			inputStream.close();
		}
	}

	private Map<String, String> getProjecAndClienttName(String summary) {

		Map<String, String> map = new HashMap<>();
		String[] eventData = summary.split(" ");	
		for (String string : eventData) {

			String[] keyValue = string.split(":");
			if (keyValue != null && keyValue.length == 2) {
				map.put(keyValue[0].trim(), keyValue[1].trim());
			}
		}
		return map;
	}

}