package net.fseconomy.servlets;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Timestamp;
import java.util.*;
import java.util.Map.Entry;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.fseconomy.data.*;
import net.fseconomy.util.Converters;
import net.fseconomy.util.Formatters;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.store.MemoryStoreEvictionPolicy;

/*
 * FS Economy
 * Copyright (C) 2005  Marty Bochane
 *  
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 * 
 */

/* service provider table - airboss 12/27/11
delimiter ;
CREATE TABLE `serviceproviders` (
  `id` int(1) unsigned NOT NULL AUTO_INCREMENT,
  `owner` int(1) NOT NULL,
  `alternate` int(1) DEFAULT NULL,
  `name` varchar(45) NOT NULL DEFAULT '',
  `ip` varchar(40) NOT NULL DEFAULT '',
  `url` varchar(200) NOT NULL DEFAULT '',
  `description` varchar(255) NOT NULL DEFAULT '',
  `notes` text,
  `key` char(10) DEFAULT NULL,
  `status` int(1) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=MyISAM AUTO_INCREMENT=14 DEFAULT CHARSET=latin1 COMMENT='User service providers that access the data feeds frequently';
*/

public class Datafeed extends HttpServlet 
{
	Data data;

	private static final long serialVersionUID = 1L;
	static final int MINFEEDDELAYMS = 1100; //default 1100 milliseconds (1.1 seconds)

	CacheManager cacheManager;  
		
	private HashMap<String, Requestor> dataFeedRequestors = new HashMap<>();
	private HashMap<String, Request> dataFeedRequests = new HashMap<>();
	private HashMap<String, FeedHitData> userFeedProcessTimes = new HashMap<>();

	//---------------------
	//Throttler settings!!
	//---------------------
	static final int USERMAXREQUESTPERWINDOW = 10;
	static final int USERREQUESTMSWINDOW = 60000; //60 seconds
	
	//---------------------
	//LIVE Settings
	//---------------------
	static final int USERLOCKOUT = 60; //Live
	static final int USERLOCKOUTMINS = 120; //Live
	
	//---------------------
	//TEST Settings
	//---------------------
	//static final int USERLOCKOUT = 60; //Testing
	//static final int USERLOCKOUTMINS = 15; //Testing
	
	//static final String XSDURL = "http://www.theflighthangar.com:8080/fseconomy";
	static String XSDURL = "http://www.fseconomy.net:81";
	
	public void init()
	{
		//Get Data Context, create it if null
		data = (Data) getServletContext().getAttribute("data");
		if (data == null)
			getServletContext().setAttribute("data", data = Data.getInstance());		

		XSDURL = Data.DataFeedUrl;
		
		//Create a CacheManager using defaults
		if( cacheManager == null)
		{
		   cacheManager = CacheManager.create();

		   //Lets create our caches
		   
		   //Max number of items in the each cache, this will need to change
		   //as the cache is used more
		   int maxElements = 10;
		   
		   //Our 5 minute cache
		   Cache cache5min = new Cache(
				     new CacheConfiguration("DataFeeds5Min", maxElements)
				       .memoryStoreEvictionPolicy(MemoryStoreEvictionPolicy.LFU)
				       .eternal(false)
				       .timeToLiveSeconds(300)
				       .timeToIdleSeconds(300)
				       .diskExpiryThreadIntervalSeconds(0));
		   
		   //Our 15 minute cache
		   Cache cache15min = new Cache(
				     new CacheConfiguration("DataFeeds15Min", maxElements)
				       .memoryStoreEvictionPolicy(MemoryStoreEvictionPolicy.LFU)
				       .eternal(false)
				       .timeToLiveSeconds(900)
				       .timeToIdleSeconds(900)
				       .diskExpiryThreadIntervalSeconds(0));

		   //Lets add our new caches to the manager for access
		   if( cacheManager.getCache("DataFeeds5Min") == null) //Added for local rebuilding, and orion initializing when it loads the war
			   cacheManager.addCache(cache5min);
		   if( cacheManager.getCache("DataFeeds15Min") == null) //Added for local rebuilding, and orion initializing when it loads the war
			   cacheManager.addCache(cache15min);
		}
	}
	
	//Entry point for Servelet
	public void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
	{
		doPost(req, resp);
	}
	
	/**
	 * Stores DataFeed hit and timing information
	 * @author Airboss 10/1/10
	 */
	private class FeedHitData
	{
		public String name;
		public long hitcount;
		public long totaltime;
		public long mintime;
		public long maxtime;
	}

	/**
	 * Stores DataFeed User Request information
	 * @author Airboss 12/4/11
	 */
	class RequestItem
	{
		public String searchparam;
		public String accesskey;
		public String accountname;
		public int servedcount;
		public Timestamp lasthit;
	}

	private class Request
	{
		public String query;
		public int servedcount;
		//public int rejectedcount;
		public Timestamp lasthit;
		public Hashtable<String, RequestItem> subqueries = new Hashtable<>();
	}

	private class Requestor
	{
		public String key;
		public String requestorname;
		public boolean isservice;
		public Timestamp ts;
		public int servedcount;
		public int rejectedcount;
		public byte limitcounter;
		public byte throttlecount;
		public Timestamp lasthit;
		public int mspassed;
		public Hashtable<String, Request> requests = new Hashtable<>();
	}
		
	/**
	 * no return, On Error tosses a DataError 
	 * @param servicekey - string access key of service
	 * @param userkey - string read access key of requester
	 * @param query - string requested datafeed
	 */
	public synchronized void doCheckKeyLastRequest(String servicekey, String userkey,  String accesskey, String query, String searchparameter) throws DataError
	{		
		String requestorkey;
		String requestorname;
		String searchparam = searchparameter == null ? "" : searchparameter;
		UserBean accessKeyAccount;
		String accountname = null;
		
		//Get our request time
		Timestamp ts = new Timestamp(System.currentTimeMillis());
		
		//Find out if service call
		boolean isService = servicekey != null;
		
		//If a service, do our setup
		if(isService)
		{
			requestorkey = servicekey;
			ServiceProviderBean spb = data.getServiceProviderByKey(servicekey);
			
			//bad key, kick it
			if(spb == null)
				throw new DataError("Invalid Service key.");

			String status = spb.getStatusString();
			requestorname = spb.getName();
			
			//if the status is anything other then active, toss a error
			if(!status.toLowerCase().contentEquals("active"))
				throw new DataError("Unable to comply, the provided ServiceKey's status is: " + status);			
		}
		else
		{
			requestorkey = userkey;
			
			//Get our user account data
			UserBean user = data.getAccountById(data.getUserGroupIdByReadAccessKey(userkey));
			
			//bad key, kick it
			if(user == null )
				throw new DataError("Invalid user key!");
			if(user.isGroup() )
				throw new DataError("Invalid key provided! Group keys are not allowed.");

			requestorname = user.getName();
		}
		
		//Check if we have a data access key
		if(accesskey != null)
		{
			accessKeyAccount = data.getAccountById(data.getUserGroupIdByReadAccessKey(accesskey));
		
			//bad key, kick it
			if(accessKeyAccount == null)
				throw new DataError("Invalid Read Access Key.");

			accountname = accessKeyAccount.getName();
		}
		
		//check if we have a cached request record already
		Requestor requestor = dataFeedRequestors.get(requestorkey);
		
		//if not, create one
		if(requestor == null)
		{			
			requestor = new Requestor();
			requestor.key = requestorkey;
			requestor.requestorname = requestorname;
			requestor.isservice = isService;
			requestor.ts = ts;
			requestor.lasthit = ts;
			requestor.throttlecount = 1;
			requestor.mspassed = 0;
			requestor.servedcount++;			
			
			dataFeedRequestors.put(requestorkey, requestor);	
			
			//Get our data access request record
			Request request = requestor.requests.get(query.toLowerCase());
			
			//If none found create one
			if( request == null)
			{
				request = new Request();
				request.query = query;
				request.lasthit = ts;
				request.servedcount++;

				RequestItem ndfi = new RequestItem();
				ndfi.searchparam = searchparam;
				ndfi.accesskey = accesskey;
				ndfi.accountname = accountname;
				ndfi.servedcount++;
				ndfi.lasthit = ts;
				request.subqueries.put(searchparam, ndfi);
				
				requestor.requests.put(query.toLowerCase(), request);			
			}			
		}
		else //if we have one fill in our hit data
		{
			//get our timing information
			requestor.mspassed = (int)(ts.getTime() - requestor.lasthit.getTime());
			requestor.lasthit = ts;
			requestor.ts = ts;

			//Get our data access request record
			Request request = requestor.requests.get(query.toLowerCase());
			
			//If none found create one
			if( request == null)
			{
				request = new Request();
				request.query = query;
				request.lasthit = ts;
				request.servedcount++;
				
				RequestItem ndfi = new RequestItem();
				ndfi.searchparam = searchparam;
				ndfi.accesskey = accesskey;
				ndfi.accountname = accountname;
				ndfi.servedcount++;
				ndfi.lasthit = ts;
				request.subqueries.put(searchparam, ndfi);
				
				requestor.requests.put(query.toLowerCase(), request);			
			}
			
			RequestItem requestitem = request.subqueries.get(searchparam);
			if(requestitem == null)
			{
				requestitem = new RequestItem();
				requestitem.searchparam = searchparam;
				requestitem.accesskey = accesskey;
				requestitem.accountname = accountname;
				requestitem.servedcount++;
				requestitem.lasthit = ts;
				request.subqueries.put(searchparam, requestitem);
			}
			
			//if the request is quicker then the required delay kick it
			if(isService)
			{
				if(requestor.mspassed < MINFEEDDELAYMS)
				{
					requestor.rejectedcount++;
					throw new DataError("Service Request Error, request was under the minimum delay of 1.1 seconds.");
				}
				else
				{
					//update user data for service call
					//Just set last service time and counter for service
					requestor.ts = ts;	
					requestor.servedcount++;
					request.lasthit = ts;
					request.servedcount++;
					requestitem.lasthit = ts;
					requestitem.servedcount++;
				}
			}
			else
			{
				//if to many requests then force lockout period
				if(requestor.throttlecount > USERLOCKOUT)
				{
					//force lockout period
					requestor.throttlecount = 0;
					requestor.lasthit.setTime(ts.getTime() + USERLOCKOUTMINS*60*1000);
				}
				else
				{
					//check to see if the last hit was over the normal user lockout period
					//if so, then clear the throttlecount and let them have the full number of requests again
					long mslasthit = ts.getTime() - requestor.lasthit.getTime();
					if(mslasthit > USERLOCKOUTMINS*60*1000)
						requestor.throttlecount = 0;
				}
				
				//update last request time
				requestor.ts = ts;
		
				//Compute time between current and previous request
				//check if we are in a lockout
				if(ts.before(requestor.lasthit))
					requestor.mspassed = (int)(ts.getTime() - requestor.lasthit.getTime());
				else //Not a lockout, so normal
					requestor.mspassed += (int)(ts.getTime() - requestor.lasthit.getTime());
				
				//If we are in a lockout period, or if we have requested to many feeds in the window reject it
				if(requestor.mspassed < 0 || (requestor.mspassed <= USERREQUESTMSWINDOW && requestor.limitcounter > USERMAXREQUESTPERWINDOW))
				{			
					requestor.rejectedcount++;
					requestor.ts = ts;
		
					if(requestor.mspassed < 0)
						throw new DataError("You have exceeded the standard accounts allotment of requests, you are now in a lockout period for " + TimeToHrsMins(Math.abs(requestor.mspassed/1000)) + " (HR:MN).");
					else
						throw new DataError("To many requests in " + USERREQUESTMSWINDOW/1000 + " second period (Max=" + USERMAXREQUESTPERWINDOW +").");
				}
				
				//reset if we have moved past the current delay window
				if(requestor.mspassed >= USERREQUESTMSWINDOW)
				{
					requestor.limitcounter = 0;
					requestor.mspassed = 0;
				}
				
				//update our last access and Increment the counters		
				requestor.lasthit = ts;
				requestor.limitcounter++;
				requestor.throttlecount++;
				requestor.servedcount++;
				request.lasthit = ts;
				request.servedcount++;
				requestitem.lasthit = ts;
				requestitem.servedcount++;
			}
		}
	}
		
	/**
	 * return Updates HashMap for query name, incrementing count
	 * @param query - query name of requested XMLFeed
	 */
	private synchronized FeedHitData doFeedHit(String query)
	{
		//See if already logged
		FeedHitData fd = userFeedProcessTimes.get(query);
		
		//If not found, create an entry
		if( fd == null)
		{
			FeedHitData nfd = new FeedHitData();
			nfd.name = query;
			userFeedProcessTimes.put(query, nfd);
			fd = nfd;
		}
		
		//increment counter
		fd.hitcount++;	
		
		return fd;
	}
	
	//Update feed min/max times
	private synchronized void doElapsedTimeUpdate(FeedHitData fhd, long starttime, long endtime)
	{
		long elapsed = endtime - starttime;
		
		fhd.totaltime += elapsed;

		if(fhd.mintime==0)
			fhd.mintime=elapsed;
		
		if(fhd.mintime > elapsed)
			fhd.mintime = elapsed;
		
		if(fhd.maxtime < elapsed)
			fhd.maxtime = elapsed;
	}
	
	//Main Processing to determine feed to service with associated checks
	public void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
	{		
		//default values 
		String output;
		
		//get our parameters
		String query = req.getParameter("query");
		String searchparam = req.getParameter("search") == null ? "" : req.getParameter("search");
		String serviceKey = req.getParameter("servicekey");
		String userKey = req.getParameter("userkey");
		String readAccessKey = req.getParameter("readaccesskey");
		
		try
		{
            //Check that we have a query and access key minimum
            if ((serviceKey == null && (userKey == null)) || (query == null))
                throw new DataError("servicekey or userkey, and query parameters expected.");

            boolean isAircraftStatus = query.toLowerCase().contentEquals("aircraft") && searchparam.toLowerCase().contentEquals("status");
			
			if(!isAircraftStatus)
				doCheckKeyLastRequest(serviceKey, userKey, readAccessKey, query, searchparam);

			//updates hit counter for requested feed, returns object to update
			FeedHitData fhd = doFeedHit(query + searchparam);
			
			//start time 
			long starttime = System.currentTimeMillis();

			if (query.equalsIgnoreCase("Aircraft"))
				output = Aircraft(req);
			else if (query.equalsIgnoreCase("AllIn"))
				output = AllInAssignments(req);
			else if (query.equalsIgnoreCase("Assignments"))
				output = Assignments(req);
			else if (query.equalsIgnoreCase("Commodities"))
				output = Commodities(req);
			else if (query.equalsIgnoreCase("CycleTimeStats"))
				output = CycleTimeStats(req);
			else if (query.equalsIgnoreCase("Facilities"))
				output = Facilities(req);
			else if (query.equalsIgnoreCase("Fbos"))
				output = Fbos(req);
			else if (query.equalsIgnoreCase("FeedRequestStats"))
				output = FeedRequestStats(req);
			else if (query.equalsIgnoreCase("FeedHitStats"))
				output = FeedHitStats(req);
			else if (query.equalsIgnoreCase("FeedStatsReset"))
				output = FeedStatsReset(req);
			else if (query.equalsIgnoreCase("FlightLogs"))
				output = FlightLogs(req);
			else if (query.equalsIgnoreCase("Group"))
				output = Group(req);
			else if (query.equalsIgnoreCase("Icao"))
				output = Icao(req);
			else if (query.equalsIgnoreCase("Payments"))
				output = Payments(req);
			else if (query.equalsIgnoreCase("ServiceRequestStats"))
				output = ServiceRequestStats(req);
			else if (query.equalsIgnoreCase("Statistics"))
				output = Statistics(req);
			else
				throw new DataError("Query not found!");

			//Note: timing info is ignored if error thrown, only interested in normal run time
			//end time
			long endtime = System.currentTimeMillis();
			
			//update timing info
			doElapsedTimeUpdate(fhd, starttime, endtime);
		} 
		catch (DataError err)
		{
			output = "<Error>" + err.getMessage() + "</Error>\n";
		}

		//setup for our response
		resp.setHeader("Cache-Control", "no-cache");
		PrintWriter out = resp.getWriter();

		//check to see what format was requested, default is XML
		String format = req.getParameter("format");
		if(format != null && format.compareToIgnoreCase("csv")==0)
		{
			resp.setContentType("application/x-excel; charset=UTF-8");

			String fname = req.getParameter("file");
			if(fname != null)
				resp.addHeader("Content-Disposition", "attachment;filename=" + fname + ".csv");
			else
				resp.addHeader("Content-Disposition", "attachment;filename=" + query + ".csv");

			out.println(output);		
		}
		else
		{
			resp.setContentType("text/xml; charset=UTF-8");
			out.println(output);		
		}
		
		resp.flushBuffer();
	}

	//Monitor AllIn assignment generation
	private String AllInAssignments(HttpServletRequest req) throws DataError
	{
		//validate that we have all needed parameters
		CheckParameters(req, DFPS.format);
		
		//generate output buffer
		Converters.csvBuffer csvoutput = null;
		Converters.xmlBuffer xmloutput = null;
		
		String format = req.getParameter("format");
		boolean csvformat = format.compareToIgnoreCase("csv") == 0;
		
		if(csvformat)
			csvoutput = new Converters.csvBuffer();
		else
			xmloutput = new Converters.xmlBuffer();

		//Get key parameter to authorize access
		String key = req.getParameter("admin");

		if( !key.contentEquals("notforeveryone"))
		{
			throw new DataError("You do not have permission to access this feed!");
		}
		else
		{
			if(csvformat && csvoutput.isHeaderEmpty())
			{
				csvoutput.appendHeaderItem("TemplateId");        
				csvoutput.appendHeaderItem("Id");        
				csvoutput.appendHeaderItem("Aircraft");        
				csvoutput.appendHeaderItem("MakeModel");        
				csvoutput.appendHeaderItem("To");        
				csvoutput.appendHeaderItem("From");
				csvoutput.appendHeaderItem("Distance");
				csvoutput.appendHeaderItem("Bearing");
				csvoutput.appendHeaderItem("Assignment");
				csvoutput.appendHeaderItem("Pay");
				csvoutput.appendHeaderItem("ExpireDateTime");		
			}	
			
			AssignmentBean[] assignments;
			assignments = data.getAssignmentsSQL("SELECT * FROM assignments WHERE aircraft is not null");

            for (AssignmentBean assignment : assignments)
            {
                String noHTMLAssignment = assignment.getSCargo().replaceAll("<.*?>", "");

                if (csvformat)
                {
                    csvoutput.append(assignment.getFromTemplate());
                    csvoutput.append(assignment.getId());
                    csvoutput.append(assignment.getAircraft());
                    AircraftBean[] ac = data.getAircraftByRegistration(assignment.getAircraft());
                    csvoutput.append(ac[0].getMakeModel());
                    csvoutput.append(assignment.getFrom());
                    csvoutput.append(assignment.getTo());
                    csvoutput.append(assignment.getDistance());
                    csvoutput.append(assignment.getBearing());
                    csvoutput.append(noHTMLAssignment);
                    csvoutput.appendMoney(assignment.calcPay());
                    csvoutput.append(assignment.getExpires() == null ? "9999/1/1 00:00:00" : assignment.getExpiresGMTDate());
                    csvoutput.newrow();
                }
                else
                {
                    xmloutput.append("<Assignment>\n");
                    xmloutput.append("templateId", assignment.getFromTemplate());
                    xmloutput.append("id", assignment.getId());
                    xmloutput.append("aircraft", assignment.getAircraft());
                    AircraftBean[] ac = data.getAircraftByRegistration(assignment.getAircraft());
                    xmloutput.append("makemodel", ac[0].getMakeModel());
                    xmloutput.append("from", assignment.getFrom());
                    xmloutput.append("to", assignment.getTo());
                    xmloutput.append("distance", assignment.getDistance());
                    xmloutput.append("bearing", assignment.getBearing());
                    xmloutput.append("assignment", Converters.XMLHelper.protectSpecialCharacters(assignment.getSCargo()));
                    xmloutput.appendMoney("pay", assignment.calcPay());
                    xmloutput.append("expires", assignment.getExpires() == null ? "9999/1/1 00:00:00" : assignment.getExpiresGMTDate());
                    xmloutput.append("</Assignment>\n");
                }
            }
		}
		if(csvformat)
			return csvoutput.toString();
		else
		{
			return GetXMLHeader() + "<AllInItems>\n" + xmloutput.toString() + "</AllInItems>\n";
		}
	}

	//Returns Maintenance Cycle timing data, XML only
	private String CycleTimeStats(HttpServletRequest req) throws DataError
	{
		Converters.xmlBuffer xmloutput;
		
		//Get key parameter to authorize access
		String key = req.getParameter("admin");

		//generate output buffer
		xmloutput = new Converters.xmlBuffer();

		if( !key.contentEquals("notforeveryone"))
		{
			throw new DataError("No Access!");
		}
		else
		{		
			MaintenanceCycle.CycleTimeData ctd = MaintenanceCycle.getCycleTimeData();			
			
			if(ctd == null)
			{
				xmloutput.append("No Data Available Yet. Try again later.");
			}
			else
			{
				xmloutput.append("<RoutineCycle>\n");
				xmloutput.append("LogStart", Formatters.dateyyyymmddhhmmss.format(ctd.logstarttime[0]));			
				xmloutput.append("HitCount", ctd.hitcount[0]);			
				xmloutput.append("TotalTime", ctd.totaltime[0]);			
				xmloutput.append("MinTime", ctd.mintime[0]);
				xmloutput.append("MaxTime", ctd.maxtime[0]);
				xmloutput.append("Avg", ctd.hitcount[0] == 0 ? 0 : ctd.totaltime[0] / ctd.hitcount[0]);
				xmloutput.append("</RoutineCycle>\n");
				xmloutput.append("<DailyCycle>\n");
				xmloutput.append("LogStart", Formatters.dateyyyymmddhhmmss.format(ctd.logstarttime[1]));
				xmloutput.append("HitCount", ctd.hitcount[1]);			
				xmloutput.append("TotalTime", ctd.totaltime[1]);			
				xmloutput.append("MinTime", ctd.mintime[1]);
				xmloutput.append("MaxTime", ctd.maxtime[1]);
				xmloutput.append("Avg", ctd.hitcount[1] == 0 ? 0 : ctd.totaltime[1] / ctd.hitcount[1]);
				xmloutput.append("</DailyCycle>\n");
			}
		}		
		return "<DataFeedCycleTimeData>\n" + xmloutput.toString() + "</DataFeedCycleTimeData>\n";	
	}

	//Resets All feed timing data, XML only
	private String FeedStatsReset(HttpServletRequest req) throws DataError
	{
		//generate output buffer
		Converters.xmlBuffer xmloutput;

		//Get key parameter to authorize access
		String key = req.getParameter("admin");

		//generate output buffer
		xmloutput = new Converters.xmlBuffer();

		if( !key.contentEquals("notforeveryone"))
		{
			throw new DataError("You do not have permission to access this feed!");
		}
		else
		{
			dataFeedRequestors.clear();
			dataFeedRequests.clear();
			userFeedProcessTimes.clear();
			
			xmloutput.append("<Message>XMLFeed Stats cleared!</Message>\n");			
		}
		return "<DataFeedStatsClear>\n" + xmloutput.toString() + "</DataFeedStatsClear>\n";	
	}

	//Returns Timing data for serving the distinct feed requests
	private String FeedHitStats(HttpServletRequest req) throws DataError
	{
		Converters.xmlBuffer xmloutput;
		
		//Get key parameter to authorize access
		String key = req.getParameter("admin");
		
		//generate output buffer
		xmloutput = new Converters.xmlBuffer();

		if( !key.contentEquals("notforeveryone"))
		{
			throw new DataError("You do not have permission to access this feed!");
		}
		else
		{		
			Set<Entry<String, FeedHitData>> myset = userFeedProcessTimes.entrySet();

            //dump out our HashMap data
            for (Entry<String, FeedHitData> aMyset : myset)
            {
                FeedHitData fd = aMyset.getValue();

                xmloutput.append("<DataFeed>\n");
                xmloutput.append("Name", fd.name);
                xmloutput.append("HitCount", fd.hitcount);
                xmloutput.append("AvgTime", fd.totaltime / fd.hitcount);
                xmloutput.append("MinTime", fd.mintime);
                xmloutput.append("MaxTime", fd.maxtime);
                xmloutput.append("</DataFeed>\n");
            }
		}

		return "<DataFeedHitStats>\n" + xmloutput.toString() + "</DataFeedHitStats>\n";	
	}

	//Returns service request data
	private String ServiceRequestStats(HttpServletRequest req) throws DataError
	{
		Converters.xmlBuffer xmloutput;
		
		//Get key parameter to authorize access
		String key = req.getParameter("admin");
		
		//generate output buffer
		xmloutput = new Converters.xmlBuffer();

		if( !key.contentEquals("notforeveryone"))
		{
			throw new DataError("You do not have permission to access this feed!");
		}
		else
		{		
			Set<Entry<String, Requestor>> myset = dataFeedRequestors.entrySet();

            //dump out our HashMap data
            for (Entry<String, Requestor> aMyset : myset)
            {
                Requestor rd = aMyset.getValue();

                xmloutput.append("<Requester>\n");
                xmloutput.append("Name", Converters.XMLHelper.protectSpecialCharacters(rd.requestorname));
                xmloutput.append("LastTimestamp", rd.ts.toString());
                xmloutput.append("ServedCount", rd.servedcount);
                xmloutput.append("RejectedCount", rd.rejectedcount);
                xmloutput.append("</Requester>\n");
            }
		}

		return "<ServiceFeedRequests>\n" +xmloutput.toString() + "</ServiceFeedRequests>\n";	
	}

	//Returns User Request data
	private String FeedRequestStats(HttpServletRequest req) throws DataError
	{
		Converters.xmlBuffer xmloutput;
		
		//Get key parameter to authorize access
		String key = req.getParameter("admin");
		
		//generate output buffer
		xmloutput = new Converters.xmlBuffer();

		if( !key.contentEquals("notforeveryone"))
		{
			throw new DataError("You do not have permission to access this feed!");
		}
		else
		{		
			Set<Entry<String, Requestor>> myset = dataFeedRequestors.entrySet();

            //dump out our HashMap data
            for (Entry<String, Requestor> aMyset : myset)
            {
                Requestor requestor = aMyset.getValue();

                Set<Entry<String, Request>> myset2 = requestor.requests.entrySet();
                Iterator<Entry<String, Request>> it2 = myset2.iterator();

                xmloutput.append("<Requester>\n");
                xmloutput.append("RequestorName", Converters.XMLHelper.protectSpecialCharacters(requestor.requestorname));
                xmloutput.append("LastTimestamp", requestor.ts.toString());
                xmloutput.append("ServedCount", requestor.servedcount);
                xmloutput.append("RejectedCount", requestor.rejectedcount);

                while (it2.hasNext())
                {
                    Request request = it2.next().getValue();

                    Set<Entry<String, RequestItem>> myset3 = request.subqueries.entrySet();
                    Iterator<Entry<String, RequestItem>> it3 = myset3.iterator();

                    xmloutput.append("<Request servedcount=\"" + request.servedcount + "\" query=\"" + request.query + "\">\n");

                    while (it3.hasNext())
                    {
                        RequestItem item = it3.next().getValue();

                        xmloutput.append("<Item searchparam=\"" + item.searchparam + "\" accountname=\"" + item.accountname + "\" servedcount=\"" + item.servedcount + "\"/>\n");
                    }
                    xmloutput.append("</Request>\n");
                }

                xmloutput.append("</Requester>\n");
            }
		}		
		return "<DataFeedRequests>\n" + xmloutput.toString() + "</DataFeedRequests>\n";	
	}

	//Return assignment data, from either myflight for pilots, or group assignment queue
	private String Assignments(HttpServletRequest req) throws DataError
	{
		int id;
		AssignmentBean[] assignments;

		//validate that we have all needed parameters
		CheckParameters(req, DFPS.format, DFPS.search);

		//Get our parameters
		String searchParam = req.getParameter("search");
		
		if(searchParam.toLowerCase().contains("lastupdate"))
		{
			Converters.xmlBuffer xmloutput = new Converters.xmlBuffer();
			
			xmloutput.append("<AssignmentUpdateStatus>\n");				
			xmloutput.append("LastUpdate", Formatters.dateDataFeed.format(MaintenanceCycle.assignmentsLastUpdate));
			xmloutput.append("</AssignmentUpdateStatus>\n");
			
			return xmloutput.toString();
		}
		else
		{
			CheckParameters(req, DFPS.readaccesskey);
			
			//we only allow search by key here
			if(!searchParam.equals("key"))
				throw new DataError("Invalid search parameter, allowed values [key].");
				
			//get our read access key and validate it
			String readAccessKey = req.getParameter("readaccesskey");
			id = data.getUserGroupIdByReadAccessKey(readAccessKey);
			if(id == -1)
				throw new DataError("No User or Group found for provided ReadAccessKey.");
	
			UserBean account = data.getAccountById(id);		
			
			//Get our name parameter for the make/model we want to retrieve
			String format = req.getParameter("format");
			boolean csvformat = format.compareToIgnoreCase("csv") == 0;
					
			//generate output buffer
			Converters.csvBuffer csvoutput = null;
			Converters.xmlBuffer xmloutput = null;
			if(csvformat)
				csvoutput = new Converters.csvBuffer();
			else
				xmloutput = new Converters.xmlBuffer();
	
			//only one search type right now
			if(account.isGroup())
				assignments = data.getAssignmentsForGroup(account.getId(), true);
			else
				assignments = data.getAssignmentsForUser(account.getId());					
			
			if(csvformat)
				AddCSVAssignmentItems(csvoutput, assignments, account.getId());
			else
				AddXMLAssignmentItems(xmloutput, assignments, account.getId());			
	
			if(csvformat)
				return csvoutput.toString();
			else
			{
				String xsd = GetXsdByQuery("Assignments");
				return GetXMLHeader() + "<AssignmentItems total=\"" + assignments.length + "\"" + xsd + ">\n" + xmloutput.toString() + "</AssignmentItems>\n";
			}
		}
	}
	
	//Return user or group goods
	private String Commodities(HttpServletRequest req) throws DataError
	{
		//validate that we have all needed parameters
		CheckParameters(req, DFPS.format, DFPS.search, DFPS.readaccesskey);
		
		Converters.csvBuffer csvoutput = null;
		Converters.xmlBuffer xmloutput = null;
		
		//Get our name parameter for the make/model we want to retrieve
		String readAccessKey = req.getParameter("readaccesskey");
		String format = req.getParameter("format");
		boolean csvformat = format.compareToIgnoreCase("csv") == 0;
		
		//generate output buffer
		if(csvformat)
			csvoutput = new Converters.csvBuffer();
		else
			xmloutput = new Converters.xmlBuffer();

		//escape single quotes contained in the string
		int id = data.getUserGroupIdByReadAccessKey(readAccessKey);
		if(id == -1)
			throw new DataError("No User or Group found for provided ReadAccessKey.");

		UserBean account = data.getAccountById(id);
				
		GoodsBean[] goods = data.getGoodsForAccountAvailable(account.getId());

        for (GoodsBean good : goods)
        {
            if (csvformat)
            {
                if (csvoutput.isHeaderEmpty())
                {
                    csvoutput.appendHeaderItem("Location");
                    csvoutput.appendHeaderItem("Commodity");
                    csvoutput.appendHeaderItem("Amount");
                }
                csvoutput.append(good.getLocation());
                csvoutput.append(good.getCommodity());
                csvoutput.append(good.getAmount() + " kg");
                csvoutput.newrow();
            }
            else
            {
                xmloutput.append("<Commodity>\n");
                xmloutput.append("Location", good.getLocation());
                xmloutput.append("Type", good.getCommodity());
                xmloutput.append("Amount", good.getAmount() + " kg");
                xmloutput.append("</Commodity>\n");
            }
        }

		if(csvformat)
			return csvoutput.toString();
		else
		{
			String xsd = GetXsdByQuery("Commodities");
			return GetXMLHeader() + "<CommodityItems" + xsd + ">\n" + xmloutput.toString() + "</CommodityItems>\n";
		}
	}

	//return user or group flight statistics
	private String Statistics(HttpServletRequest req) throws DataError
	{
		//validate that we have all needed parameters
		CheckParameters(req, DFPS.format, DFPS.search, DFPS.readaccesskey);

		Converters.csvBuffer csvoutput = null;
		Converters.xmlBuffer xmloutput = null;
		
		//Get our name parameter for the make/model we want to retrieve
		String readAccessKey = req.getParameter("readaccesskey");
		String format = req.getParameter("format");
		boolean csvformat = format.compareToIgnoreCase("csv") == 0;
		
		//generate output buffer
		if(csvformat)
			csvoutput = new Converters.csvBuffer();
		else
			xmloutput = new Converters.xmlBuffer();

		//escape single quotes contained in the string
		int id = data.getUserGroupIdByReadAccessKey(readAccessKey);
		if(id == -1)
			throw new DataError("No User or Group found for provided ReadAccessKey.");
		
		UserBean account = data.getAccountById(id);
		String name = Converters.XMLHelper.protectSpecialCharacters(account.getName());
		
		int flights = 0;
		int totalMiles = 0;
		String time = "";
		Data.statistics[] stats = data.getStatistics();
		if(stats == null)
		{
			throw new DataError("Statistics not calculated yet. Try again in a few minutes.");
		}

        for (Data.statistics entry : stats)
        {
            if (entry.accountName.contentEquals(account.getName()))
            {
                flights = entry.flights;
                totalMiles = entry.totalMiles;
                time = TimeToHrsMins(entry.totalFlightTime);
                break;
            }
        }

		if(csvformat)
		{
			if(csvoutput.isHeaderEmpty())
			{
				csvoutput.appendHeaderItem("Personal_balance");
				csvoutput.appendHeaderItem("Bank_balance");
				csvoutput.appendHeaderItem("flights");
				csvoutput.appendHeaderItem("Total_Miles");
				csvoutput.appendHeaderItem("Time_Flown");
			}
			csvoutput.appendMoney(account.getMoney());
			csvoutput.appendMoney(account.getBank());
			csvoutput.append(flights);
			csvoutput.append(totalMiles);
			csvoutput.append(time);			
		}
		else
		{
			xmloutput.append("<Statistic account=\"" + name + "\">\n");
			xmloutput.appendMoney("Personal_balance", account.getMoney());
			xmloutput.appendMoney("Bank_balance", account.getBank());
			xmloutput.append("flights", flights);
			xmloutput.append("Total_Miles", totalMiles);
			xmloutput.append("Time_Flown", time);
			xmloutput.append("</Statistic>\n");
		}
		if(csvformat)
			return csvoutput.toString();
		else
		{
			String xsd = GetXsdByQuery("Statistics");
			return GetXMLHeader() + "<StatisticItems" + xsd + ">\n" + xmloutput.toString() + "</StatisticItems>\n";
		}
	}

	//return flight logs for user or group by month/year
	private String FlightLogs(HttpServletRequest req) throws DataError
	{
		String results;
		
		//validate that we have all needed parameters
		CheckParameters(req, DFPS.format, DFPS.search);

		//Get our parameters
		String searchParam = req.getParameter("search");

        switch (searchParam)
        {
            case "id":
            {
                CheckParameters(req, DFPS.fromid);

                String readAccessKey;
                String reg = null;
                int id;
                UserBean account = null;

                //is key or reg?
                if (req.getParameter("readaccesskey") != null)
                {
                    CheckParameters(req, DFPS.readaccesskey);

                    //Get our name parameter for the make/model we want to retrieve
                    readAccessKey = req.getParameter("readaccesskey");

                    //escape single quotes contained in the string
                    id = data.getUserGroupIdByReadAccessKey(readAccessKey);

                    if (id == -1)
                        throw new DataError("No User or Group found for provided ReadAccessKey.");

                    account = data.getAccountById(id);
                }
                else if (req.getParameter("aircraftreg") != null)
                {
                    CheckParameters(req, DFPS.aircraftreg);

                    //See if there is a specific aircraft the information is desired for
                    reg = req.getParameter("aircraftreg");
                }
                else
                {
                    throw new DataError("Invalid search parameter!");
                }

                int fromid;
                String sfromid = req.getParameter("fromid");
                fromid = Integer.parseInt(sfromid);

                LogBean[] log;
                if (reg != null && !reg.isEmpty())
                {
                    log = data.getLogForAircraftFromId(reg, fromid);
                }
                else if (req.getParameter("type") != null && req.getParameter("type").toLowerCase().contains("groupaircraft"))
                {
                    AircraftBean[] aircraft = data.getAircraftOwnedByUser(account.getId());
                    if (aircraft.length > 0)
                    {
                        String regs = "";
                        for (int c = 0; c < aircraft.length; c++)
                        {
                            if (c == 0)
                            {
                                regs = "'" + aircraft[c].getRegistration() + "'";
                            }
                            else
                            {
                                regs = regs + "'" + aircraft[c].getRegistration() + "'";
                            }

                            if ((c + 1) < aircraft.length)
                                regs = regs + ", ";
                        }
                        log = data.getLogForGroupFromRegistrations(regs, fromid);
                    }
                    else
                    {
                        log = null;
                    }
                }
                else if (account.isGroup())
                {
                    log = data.getLogForGroupFromId(account.getId(), fromid);
                }
                else
                {
                    log = data.getLogForUserFromId(account.getName(), fromid);
                }
                results = ProcessFlightLogs(req, log, "FlightLogsFromId");

                break;
            }
            case "monthyear":
            {
                CheckParameters(req, DFPS.month, DFPS.year);

                //Get our name parameter for the make/model we want to retrieve
                String readAccessKey;
                String reg = null;
                int id;
                UserBean account = null;

                //is key or reg?
                if (req.getParameter("readaccesskey") != null)
                {
                    CheckParameters(req, DFPS.readaccesskey);

                    //Get our name parameter for the make/model we want to retrieve
                    readAccessKey = req.getParameter("readaccesskey");

                    //escape single quotes contained in the string
                    id = data.getUserGroupIdByReadAccessKey(readAccessKey);
                    if (id == -1)
                        throw new DataError("No User or Group found for provided ReadAccessKey.");

                    account = data.getAccountById(id);
                }
                else if (req.getParameter("aircraftreg") != null)
                {
                    CheckParameters(req, DFPS.aircraftreg);

                    //See if there is a specific aircraft the information is desired for
                    reg = req.getParameter("aircraftreg");
                }
                else
                {
                    throw new DataError("Invalid search parameter!");
                }

                //Month parameter is required, so if there is an error send back the error message
                int month = ValidateMonth(req.getParameter("month"));
                int year = ValidateYear(req.getParameter("year"), month);

                LogBean[] log;
                if (reg != null && !reg.isEmpty())
                    log = data.getLogForAircraftByMonth(reg, month, year);
                else if (account.isGroup())
                    log = data.getLogForGroupByMonth(account.getId(), month, year);
                else
                    log = data.getLogForUserByMonth(account.getName(), month, year);

                results = ProcessFlightLogs(req, log, "FlightLogsByMonthYear");
                break;
            }
            default:
                throw new DataError("Invalid search parameter!");
        }
		return results;
	}

	private String Payments(HttpServletRequest req) throws DataError
	{
		PaymentBean[] log = null;
		
		//validate that we have all needed parameters
		CheckParameters(req, DFPS.format, DFPS.search, DFPS.readaccesskey);
		
		//Get our name parameter for the make/model we want to retrieve
		String readAccessKey = req.getParameter("readaccesskey");

		//escape single quotes contained in the string
		int id = data.getUserGroupIdByReadAccessKey(readAccessKey);
		if(id == -1)
			throw new DataError("No User or Group found for provided ReadAccessKey.");

		UserBean account = data.getAccountById(id);
		
		//Get our parameters
		String searchParam = req.getParameter("search");
		
		if(searchParam.equals("monthyear"))
		{
			//Month parameter is required, so if there is an error send back the error message
			int month = ValidateMonth(req.getParameter("month"));
			int year = ValidateYear(req.getParameter("year"), month);

			log = data.getPaymentsForIdByMonth(account.getId(), month, year);
		}
		else if(searchParam.equals("id"))
		{
			int fromid;
			String sfromid = req.getParameter("fromid");
			if( sfromid != null)
				fromid = Integer.parseInt(sfromid);
			else
				throw new DataError("Error\nMissing parameter FromId.");
			
			String sql = "SELECT * FROM payments where user = " + id + " AND id > " + fromid 
			+ " UNION SELECT * from payments where otherparty = " + id + " AND id > " + fromid 
			+ " ORDER BY id LIMIT 500"; 
			
			log = data.getPaymentLogSQL(sql);
		}
		return ProcessPayments(req, log, "PaymentsByMonthYear");
	}

	private String Group(HttpServletRequest req) throws DataError
	{
		String results;
		
		//validate that we have all needed parameters
		CheckParameters(req, DFPS.format, DFPS.search, DFPS.readaccesskey);

		Converters.csvBuffer csvoutput = null;
		Converters.xmlBuffer xmloutput = null;
		
		//Get our parameters
		String searchParam = req.getParameter("search");
		String readAccessKey = req.getParameter("readaccesskey");
		String format = req.getParameter("format");
		boolean csvformat = format.compareToIgnoreCase("csv") == 0;
		
		//generate output buffer
		if(csvformat)
			csvoutput = new Converters.csvBuffer();
		else
			xmloutput = new Converters.xmlBuffer();

		//escape single quotes contained in the string
		int id = data.getUserGroupIdByReadAccessKey(readAccessKey);
		if(id == -1)
			throw new DataError("No User or Group found for provided ReadAccessKey.");

		UserBean account = data.getAccountById(id);
		String groupname = Converters.XMLHelper.protectSpecialCharacters(account.getName());
		if(!account.isGroup())
			throw new DataError("Requires a Group ReadAccessKey.");
		
		if(searchParam.equals("members"))
		{
			UserBean[] members = data.getUsersForGroup(id);
			for (int c = 0; c < members.length; c++)
			{
				data.reloadMemberships(members[c]);

				String level = "ERROR";
				if(members[c].getMemberships().containsKey(id))
				{
					Data.groupMemberData gmd = members[c].getMemberships().get(id);
					level = UserBean.getGroupLevelName(gmd.memberLevel);
				}

				if(csvformat)
				{
					if(c == 0) 
					{
						csvoutput.appendHeaderItem("Group");
						csvoutput.appendHeaderItem("Member");
						csvoutput.appendHeaderItem("Status");
					}
					csvoutput.append(account.getName());
					csvoutput.append(members[c].getName());
					csvoutput.append(level);
					csvoutput.newrow();
				}
				else
				{
					xmloutput.appendOpenTag("Member");
					xmloutput.append("Name", Converters.XMLHelper.protectSpecialCharacters(members[c].getName()));
					xmloutput.append("Status", level);
					xmloutput.appendCloseTag("Member");
				}
			}
			if(csvformat)
				results = csvoutput.toString();
			else
			{
				String xsd = GetXsdByQuery("Members");
				results = GetXMLHeader() + "<MemberItems name=\"" + groupname + "\" total=\"" + members.length + "\"" + xsd + ">\n" + xmloutput.toString() + "</MemberItems>\n";
			}
		}
		else
		{
			throw new DataError("Invalid search parameter!");
		}
		
		return results;
	}

	private String Facilities(HttpServletRequest req) throws DataError
	{
		String result;
		
		CheckParameters(req, DFPS.format, DFPS.search, DFPS.readaccesskey);

		//Get our parameters
		String searchParam = req.getParameter("search");
		if(!searchParam.equals("key"))
			throw new DataError("Invalid search parameter, only key is allowed.");
		
		//Get our name parameter for the make/model we want to retrieve
		String readAccessKey = req.getParameter("readaccesskey");

		int id = data.getUserGroupIdByReadAccessKey(readAccessKey);
		if(id == -1)
			throw new DataError("No User or Group found for provided ReadAccessKey.");
		
		//get the facilities by the requester
		FboFacilityBean[] facs = data.getFboFacilitiesByOccupant(id);
		result = ProcessFacilities(req, facs, "Facilities");
		
		return result;
	}

	private String Fbos(HttpServletRequest req) throws DataError
	{
		String result = null;
		
		CheckParameters(req, DFPS.format, DFPS.search);

		//Get our parameters
		String searchParam = req.getParameter("search");

        switch (searchParam)
        {
            case "key":
            {
                CheckParameters(req, DFPS.format, DFPS.readaccesskey);
                //Get our name parameter for the make/model we want to retrieve
                String readAccessKey = req.getParameter("readaccesskey");

                //escape single quotes contained in the string
                int id = data.getUserGroupIdByReadAccessKey(readAccessKey);
                if (id == -1)
                    throw new DataError("No User or Group found for provided ReadAccessKey.");

                //get selected aircraft
                FboBean[] fbos = data.getFboByOwner(id);
                result = ProcessFbos(req, fbos, "FboByKey");
                break;
            }
            case "monthlysummary":
            {
                CheckParameters(req, DFPS.format, DFPS.readaccesskey, DFPS.month, DFPS.year, DFPS.icao);

                Converters.csvBuffer csvoutput = null;
                Converters.xmlBuffer xmloutput = null;

                //Get our name parameter for the make/model we want to retrieve
                String readAccessKey = req.getParameter("readaccesskey");
                String format = req.getParameter("format");
                boolean csvformat = format.compareToIgnoreCase("csv") == 0;

                //generate output buffer
                if (csvformat)
                    csvoutput = new Converters.csvBuffer();
                else
                    xmloutput = new Converters.xmlBuffer();

                //escape single quotes contained in the string
                int id = data.getUserGroupIdByReadAccessKey(readAccessKey);
                if (id == -1)
                    throw new DataError("No User or Group found for provided ReadAccessKey.");

                UserBean account = data.getAccountById(id);

                String icao = req.getParameter("icao");

                //if key owns fbo at icao continue, else throw access error!
                String sql = "select * from fbo where location='" + icao + "' and owner=" + account.getId();
                FboBean[] fbo = data.getFboSql(sql);
                if (fbo == null || fbo.length == 0)
                    throw new DataError("No fbo found for provided ReadAccessKey.");

                //Month parameter is required, so if there is an error send back the error message
                int month = ValidateMonth(req.getParameter("month"));
                int year = ValidateYear(req.getParameter("year"), month);

                if (csvformat)
                    AddCSVFboPaymentSummaryItem(csvoutput, account, fbo[0], icao, month, year);
                else
                    AddXMLFboPaymentSummaryItem(xmloutput, account, fbo[0], icao, month, year);

                if (csvformat)
                    result = csvoutput.toString();
                else
                {
                    String xsd = GetXsdByQuery("FboMonthlySummary");
                    result = GetXMLHeader() + "<FboMonthlySummaryItems" + xsd + ">\n" + xmloutput.toString() + "</FboMonthlySummaryItems>\n";
                }
                break;
            }
            case "forsale":
            {
                //get list of aircraft for sale
                FboBean[] fbos = data.getFboForSale();

                result = ProcessFbos(req, fbos, "FbosForSale");
                break;
            }
        }
		
		return result;
	}

	private String Icao(HttpServletRequest req) throws DataError
	{
		String results = null;
		
		CheckParameters(req, DFPS.format, DFPS.search);

		//Get our parameters
		String searchParam = req.getParameter("search");

        switch (searchParam)
        {
            case "aircraft":
            {
                //Get our name parameter for the make/model we want to retrieve
                String icao = req.getParameter("icao");

                //get selected aircraft
                AircraftBean[] aircraft = data.getAircraftSQL("SELECT * FROM aircraft, models WHERE Upper(aircraft.location)!='DEAD' AND aircraft.model = models.id AND location='" + icao + "' ORDER BY make, models.model");

                results = ProcessAircraft(req, aircraft, "IcaoAircraft");
                break;
            }
            case "fbo":
            {
                CheckParameters(req, DFPS.icao);
                //**
                //** Cannot use ProcessFbos here because we are adding in System Fbos after the main loop
                //**
                Converters.csvBuffer csvoutput = null;
                Converters.xmlBuffer xmloutput = null;
                int numfbos = 0;

                //Get our name parameter for the make/model we want to retrieve
                String icao = req.getParameter("icao");
                String format = req.getParameter("format");
                boolean csvformat = format.compareToIgnoreCase("csv") == 0;

                //generate output buffer
                if (csvformat)
                    csvoutput = new Converters.csvBuffer();
                else
                    xmloutput = new Converters.xmlBuffer();

                FboBean[] fbos = data.getFboByLocation(icao);

                if (fbos != null && fbos.length != 0)
                {
                    //throw new DataError("No active FBO Found.\n");

                    //create an aircraft tag section for each alias
                    if (csvformat)
                        AddCSVFboItems(csvoutput, fbos, false);
                    else
                        AddXMLFboItems(xmloutput, fbos, false);

                    numfbos = fbos.length;
                }

                AirportBean airport = data.getAirport(icao);

                //Check if system resources available
                if (airport != null && (airport.isAvgas() || airport.isJetA() || airport.getSize() >= AircraftMaintenanceBean.REPAIR_AVAILABLE_AIRPORT_SIZE))
                {
                    if (csvformat)
                        AddCSVSystemFboItem(csvoutput, airport);
                    else
                        AddXMLSystemFboItem(xmloutput, airport);
                }

                //save to our output string
                String xsd = GetXsdByQuery("IcaoFbos");
                results = GetXMLHeader() + "<IcaoFboItems total=\"" + numfbos + "\"" + xsd + ">\n" + xmloutput.toString() + "</IcaoFboItems>\n";
                break;
            }
            case "jobsto":
            case "jobsfrom":
                AssignmentBean[] assignments;
                String icaos = req.getParameter("icaos");

                //Expected Icaos format: CZFA-CEX4-CFP4
                //Kick if any quotes or semicolons found, could be injected sql
                if (icaos.contains("\'") || icaos.contains(";"))
                    throw new DataError("Invalid character detected. Format to use: CZFA-CEX4-CFP4");

                icaos = "'" + icaos.replaceAll("-", "','").trim() + "'";

                if (searchParam.equals("jobsfrom"))
                {
                    assignments = data.getAssignmentsFromAirport(icaos);
                    results = ProcessJobs(req, assignments, "IcaoJobsFrom");
                }
                else
                {
                    assignments = data.getAssignmentsToAirport(icaos);
                    results = ProcessJobs(req, assignments, "IcaoJobsTo");
                }
                break;
        }
		return results;
	}

	private String Aircraft(HttpServletRequest req) throws DataError
	{
		int id = -1;
		AircraftBean[] aircraft;
		String queryname;
		
		//validate that we have all needed parameters
		CheckParameters(req, DFPS.format, DFPS.search);

		//Get our parameters
		String searchParam = req.getParameter("search");

		if(searchParam.equals("key"))
		{
			CheckParameters(req, DFPS.readaccesskey);

			String readAccessKey = req.getParameter("readaccesskey");
			id = data.getUserGroupIdByReadAccessKey(readAccessKey);
			if(id == -1)
				throw new DataError("No User or Group found for provided ReadAccessKey.");
		}
		
		if(searchParam.equals("key"))
		{
			aircraft = data.getAircraftOwnedByUser(id);
			queryname = "AircraftByKey";
		}
		else if(searchParam.equals("configs"))
		{
			return AircraftConfigs(req);
		}
		else if(searchParam.equals("aliases"))
		{
			return AircraftAliases(req);
		}
		else if(searchParam.equals("forsale"))
		{
			//get list of aircraft for sale
			aircraft = data.getAircraftForSale();
			queryname = "AircraftForSale";
		}
		else if(searchParam.equals("makemodel"))
		{
			CheckParameters(req, DFPS.makemodel);
			
			//Get our name parameter for the make/model we want to retrieve
			String aircraftname = req.getParameter("makemodel");

			//escape single quotes contained in the string
			aircraftname = aircraftname.replaceAll("'", "\\\\'").replaceAll(";","");
			
			//get selected aircraft
			aircraft = data.getAircraftSQL("SELECT * FROM aircraft, models WHERE Upper(aircraft.location)!='DEAD' AND aircraft.model = models.id AND concat(concat(models.make,' '), models.model)='" + aircraftname + "' ORDER BY make, models.model");
			queryname = "AircraftByMakeModel";
		}
		else if(searchParam.toLowerCase().equals("ownername"))
		{
			CheckParameters(req, DFPS.ownername);
			
			//Get our name parameter for the make/model we want to retrieve
			String ownersname = req.getParameter("ownername");

			//escape single quotes contained in the string
			ownersname = ownersname.replaceAll("'", "\\\\'").replaceAll(";","");
			
			//Because of the hit, we do not allow a query for bank owned aircraft
			if(ownersname.toLowerCase().equals("bank"))
				throw new DataError("You do not have access permission to access that owners aircraft");
			
			//To allow admins to examine Bank owner aircraft a special key for owner name is used
			if( ownersname.toLowerCase().equals("knabaccess"))
				ownersname = "Bank";
			
			//get selected aircraft
			aircraft = data.getAircraftSQL(
					" SELECT aircraft.*, models.* " +
					" FROM aircraft, models, accounts " +
					" WHERE Upper(aircraft.location)!='DEAD' " +
					" AND aircraft.model = models.id " +
					" AND accounts.id=aircraft.owner " +
					" AND accounts.name='" + ownersname + "' " + 
					" ORDER BY models.make, models.model");
			queryname = "AircraftByOwnerName";
		}
		else if(searchParam.toLowerCase().equals("registration"))
		{
			CheckParameters(req, DFPS.aircraftreg);
			
			//Get our name parameter for the make/model we want to retrieve
			String registration = req.getParameter("aircraftreg");

			//escape single quotes contained in the string
			registration = registration.replaceAll("'", "\\\\'").replaceAll(";","");
			
			//get selected aircraft
			aircraft = data.getAircraftByRegistration(registration);
			queryname = "AircraftByRegistration";
		}
		else if(searchParam.toLowerCase().equals("status"))
		{
			CheckParameters(req, DFPS.aircraftreg);

			Converters.xmlBuffer output = new Converters.xmlBuffer();
			
			//Get our name parameter for the make/model we want to retrieve
			String aircraftreg = req.getParameter("aircraftreg");

			//escape single quotes contained in the string
			aircraftreg = aircraftreg.replaceAll("'", "\\\\'").replaceAll(";","");
			
			//get selected aircraft
			AircraftBean [] aircrafts = data.getAircraftByRegistration(aircraftreg);
			if(aircrafts == null || aircrafts.length == 0)
				throw new DataError("No Aircraft Found.");
			
			AircraftBean ac = aircrafts[0];		

			//create an Aircraft tag
			output.append("<Aircraft>");

			if( ac.getLocation() == null)
				output.append("Status", "In Flight");
			else
				output.append("Status", "On Ground");

			output.append("Location", ac.getLocation());

			output.append("</Aircraft>");
			
			//return our generated xml
			return "<AircraftStatus registration=\"" + ac.getRegistration() + "\">\n" + output.toString() + "</AircraftStatus>\n";
		}
		else
		{
			throw new DataError("Error! Search Parameter not found!.");
		}
		
		//get selected aircraft
		
		return ProcessAircraft(req, aircraft, queryname);
	}

	private String AircraftAliases(HttpServletRequest req)
	{
		Converters.csvBuffer csvoutput = null;
		Converters.xmlBuffer xmloutput = null;
		
		String format = req.getParameter("format");
		boolean csvformat = format.compareToIgnoreCase("csv") == 0;
		
		//generate output buffer
		if(csvformat)
			csvoutput = new Converters.csvBuffer();
		else
			xmloutput = new Converters.xmlBuffer();

		//get list of aliases, returned sorted by model
		Data.AircraftAlias[] aircraft = data.getAircraftAliasesOld();
		
		//dump out our HashMap data
		if(csvformat)
		{
			csvoutput.appendHeaderItem("MakeModel");
			csvoutput.appendHeaderItem("Alias");
		}
		
		if(csvformat)
		{
            for (Data.AircraftAlias anAircraft : aircraft)
            {
                csvoutput.append(anAircraft.model);
                csvoutput.append(anAircraft.fsName);
                csvoutput.newrow();
            }
		}
		else
		{		
			//create an aircraft tag section for each alias
			for (int c=0; c < aircraft.length; c++)
			{
				xmloutput.append("<AircraftAliases>\n");
				xmloutput.append("MakeModel", aircraft[c].model);	
				xmloutput.append("Alias", Converters.XMLHelper.protectSpecialCharacters(aircraft[c].fsName));
					
				while( true ) //loop thru all the aliases for this model
				{
					if( c >= (aircraft.length-1)) break; // if we are at the end exit
					
					// if the same model add the alias and continue
					if( aircraft[c+1].model.contentEquals(aircraft[c].model) ) 
					{
						c++;
						xmloutput.append("Alias", Converters.XMLHelper.protectSpecialCharacters(aircraft[c].fsName));
					}
					else
						break; // model change
				}
				xmloutput.append("</AircraftAliases>\n");
			}		
		}		
		if(csvformat)
			return csvoutput.toString();
		else
		{
			String xsd = GetXsdByQuery("AircraftAliases");
			return GetXMLHeader() + "<AircraftAliasItems" + xsd + ">\n" + xmloutput.toString() + "</AircraftAliasItems>\n";
		}
	}

	private String AircraftConfigs(HttpServletRequest req)
	{
		Converters.csvBuffer csvoutput = null;
		Converters.xmlBuffer xmloutput = null;
		
		String format = req.getParameter("format");
		boolean csvformat = format.compareToIgnoreCase("csv") == 0;
		
		//generate output buffer
		if(csvformat)
			csvoutput = new Converters.csvBuffer();
		else
			xmloutput = new Converters.xmlBuffer();

		//get list of configs
		Data.aircraftConfigs[] aircraft = data.getAircraftConfigs();
		
		//dump out our HashMap data
		if(csvformat)
		{
			csvoutput.appendHeaderItem("MakeModel");
			csvoutput.appendHeaderItem("Crew");
			csvoutput.appendHeaderItem("Seats");
			csvoutput.appendHeaderItem("CruiseSpeed");
			csvoutput.appendHeaderItem("GPH");
			csvoutput.appendHeaderItem("FuelType");
			csvoutput.appendHeaderItem("MTOW");
			csvoutput.appendHeaderItem("EmptyWeight");
			csvoutput.appendHeaderItem("Price");
			csvoutput.appendHeaderItem("Ext1");
			csvoutput.appendHeaderItem("LTip");
			csvoutput.appendHeaderItem("LAux");
			csvoutput.appendHeaderItem("LMain");
			csvoutput.appendHeaderItem("Center1");
			csvoutput.appendHeaderItem("Center2");
			csvoutput.appendHeaderItem("Center3");
			csvoutput.appendHeaderItem("RMain");
			csvoutput.appendHeaderItem("RAux");
			csvoutput.appendHeaderItem("RTip");
			csvoutput.appendHeaderItem("RExt2");
			csvoutput.appendHeaderItem("Engines");
			csvoutput.appendHeaderItem("EnginePrice");
		}
		
		//create an aircraft tag section for each config
        for (Data.aircraftConfigs anAircraft : aircraft)
        {
            if (csvformat)
            {
                csvoutput.append(anAircraft.makemodel);
                csvoutput.append(anAircraft.crew);
                csvoutput.append(anAircraft.seats);
                csvoutput.append(anAircraft.cruisespeed);
                csvoutput.append(anAircraft.gph);
                csvoutput.append(anAircraft.fueltype);
                csvoutput.append(anAircraft.maxWeight);
                csvoutput.append(anAircraft.emptyWeight);
                csvoutput.appendMoney(anAircraft.price);
                csvoutput.append(anAircraft.fcapExt1);
                csvoutput.append(anAircraft.fcapLeftTip);
                csvoutput.append(anAircraft.fcapLeftAux);
                csvoutput.append(anAircraft.fcapLeftMain);
                csvoutput.append(anAircraft.fcapCenter);
                csvoutput.append(anAircraft.fcapCenter2);
                csvoutput.append(anAircraft.fcapCenter3);
                csvoutput.append(anAircraft.fcapRightMain);
                csvoutput.append(anAircraft.fcapRightAux);
                csvoutput.append(anAircraft.fcapRightTip);
                csvoutput.append(anAircraft.fcapExt2);
                csvoutput.append(anAircraft.engines);
                csvoutput.appendMoney(anAircraft.enginePrice);
                csvoutput.newrow();
            }
            else
            {
                xmloutput.append("<AircraftConfig>\n");
                xmloutput.append("MakeModel", anAircraft.makemodel);
                xmloutput.append("Crew", anAircraft.crew);
                xmloutput.append("Seats", anAircraft.seats);
                xmloutput.append("CruiseSpeed", anAircraft.cruisespeed);
                xmloutput.append("GPH", anAircraft.gph);
                xmloutput.append("FuelType", anAircraft.fueltype);
                xmloutput.append("MTOW", anAircraft.maxWeight);
                xmloutput.append("EmptyWeight", anAircraft.emptyWeight);
                xmloutput.appendMoney("Price", anAircraft.price);
                xmloutput.append("Ext1", anAircraft.fcapExt1);
                xmloutput.append("LTip", anAircraft.fcapLeftTip);
                xmloutput.append("LAux", anAircraft.fcapLeftAux);
                xmloutput.append("LMain", anAircraft.fcapLeftMain);
                xmloutput.append("Center1", anAircraft.fcapCenter);
                xmloutput.append("Center2", anAircraft.fcapCenter2);
                xmloutput.append("Center3", anAircraft.fcapCenter3);
                xmloutput.append("RMain", anAircraft.fcapRightMain);
                xmloutput.append("RAux", anAircraft.fcapRightAux);
                xmloutput.append("RTip", anAircraft.fcapRightTip);
                xmloutput.append("Ext2", anAircraft.fcapExt2);
                xmloutput.append("Engines", anAircraft.engines);
                xmloutput.appendMoney("EnginePrice", anAircraft.enginePrice);
                xmloutput.append("</AircraftConfig>\n");
            }
        }

		if(csvformat)
			return csvoutput.toString();
		else
		{
			String xsd = GetXsdByQuery("AircraftConfigs");
			return GetXMLHeader() + "<AircraftConfigItems total=\"" + aircraft.length + "\"" + xsd + ">\n" + xmloutput.toString() + "</AircraftConfigItems>\n";
		}
	}
	
	//---------------------------------------------------------------------------------------------
	// Helpers
	//---------------------------------------------------------------------------------------------

	enum DFPS //DataFeedParameterS
	{
		aircraft,
		aircraftreg,
		aliases,
		configs,
		icao,
		icaos,
		id,
		fbo,
		format,
		forsale,
		fromid,
		jobsto,
		jobsfrom,
		makemodel,
		members,
		month,
		ownername,
		readaccesskey,
		search,
		servicekey,
		userkey,
		year,
	}
	
	private void CheckParameters(HttpServletRequest req, DFPS ... params) throws DataError
	{
		String error = null;

        for (DFPS param1 : params)
        {
            //Get our parameter
            String param = req.getParameter(param1.name());

            if (param == null || param.isEmpty())
            {
                //if year, ignore as its allowed to be missing
                if (param1 == DFPS.year)
                {
                    continue;
                }

                if (error != null)
                {
                    error += ", " + param1.name();
                }
                else
                {
                    error = param1.name();
                }
            }
            else if (param1 == DFPS.icao && param.length() > 4)
            {
                throw new DataError("ICAO invalid format.\n");
            }
        }

		if(error != null)
			throw new DataError("Missing parameters found! [" + error + "]");
	}
	
	/**
	 * Helper function that returns a cached XML feed from the supplied cache name, and cache item
	 * @param cacheName - string identifying the cache in CacheManager cacheManager
	 * @param cacheItem - string identifying the item from the cache to return if available
	 * @return String - cached string value, or "" if not found
	 */
	public String CheckCacheForItem(String cacheName, String cacheItem)
	{
		//holds our default return value
		String s = "";

		//Lets get the passed in cache
		Cache cache = cacheManager.getCache(cacheName);
		
		//if( cache == null)
		//	throw new UserException("cache object not found");
		
		//Get our stored item
		Element e = cache.get(cacheItem);
		if( e != null) //Found it!, otherwise default is returned
			s = (String) e.getObjectValue();
		
		return s;
	}
	
	String TimeToHrsMins(int time)
	{
		int minutes = (int)((time + 30) / 60.0);
        return Formatters.oneDigit.format(minutes/60) + ":" + Formatters.twoDigits.format(minutes%60);
	}	
	
	private int ValidateMonth(String mon) throws DataError
	{
		//Month parameter is required, so if there is an error send back the error message
		int month;
		try
		{
			month = Integer.parseInt(mon);
		}
		catch(NumberFormatException e)
		{
			throw new DataError("Month parameter must be in the range of 1 to 12.");
		}
		
		//check for valid month range
		if (month < 1 || month > 12) 
		{
			throw new DataError("Month parameter must be in the range of 1 to 12.");
		}
		return month;
	}

	private int ValidateYear(String yr, int month) throws DataError
	{
		//Year is an optional parameter and if not used is not passed in the parameter list
		Calendar cal = Calendar.getInstance();
		int cyear = cal.get(Calendar.YEAR);
		int cmonth = cal.get(Calendar.MONTH) + 1;

		int year; 
		if(yr != null)
		{//Year was found, validate it			
			try
			{
				year = Integer.parseInt(yr);
			}
			catch(NumberFormatException e)
			{
				throw new DataError("Year parameter must be in the range of 2004 to " + cyear);
			}
			
			//check for valid year range
			if (year < 2004 && year > cyear) 
				throw new DataError("Year parameter must be in the range of 2004 to " + cyear);
		}
		else
		{//Year was NOT found, set it to the default year (current year, or previous year)
			year = month <= cmonth ? cyear : cyear-1;
		}		
		return year;
	}
	
	private String ProcessFlightLogs(HttpServletRequest req, LogBean[] flightlog, String queryname)
	{
		Converters.csvBuffer csvoutput = null;
		Converters.xmlBuffer xmloutput = null;
		
		String format = req.getParameter("format");
		boolean csvformat = format.compareToIgnoreCase("csv") == 0;

		//generate output buffer
		if(csvformat)
			csvoutput = new Converters.csvBuffer();
		else
			xmloutput = new Converters.xmlBuffer();

		//create an aircraft tag section for each alias
		if(csvformat)
			AddCSVFlightLogItems(csvoutput, flightlog);
		else
			AddXMLFlightLogItems(xmloutput, flightlog);					
		
		if(csvformat)
			return csvoutput.toString();
		else
		{
			String xsd = GetXsdByQuery(queryname);
			return GetXMLHeader() + "<" + queryname + " total=\"" + flightlog.length + "\"" + xsd + ">\n" + xmloutput.toString() + "</" + queryname + ">\n";		
		}
	}

	private String ProcessPayments(HttpServletRequest req, PaymentBean[] payments, String queryname)
	{
		Converters.csvBuffer csvoutput = null;
		Converters.xmlBuffer xmloutput = null;
		
		String format = req.getParameter("format");
		boolean csvformat = format.compareToIgnoreCase("csv") == 0;

		//generate output buffer
		if(csvformat)
			csvoutput = new Converters.csvBuffer();
		else
			xmloutput = new Converters.xmlBuffer();

		//create an aircraft tag section for each alias
		if(csvformat)
			AddCSVPaymentItems(csvoutput, payments);
		else
			AddXMLPaymentItems(xmloutput, payments);					
		
		if(csvformat)
			return csvoutput.toString();
		else
		{
			String xsd = GetXsdByQuery(queryname);
			return GetXMLHeader() + "<" + queryname + " total=\"" + payments.length + "\"" + xsd + ">\n" + xmloutput.toString() + "</" + queryname + ">\n";		
		}
	}

	private String ProcessJobs(HttpServletRequest req, AssignmentBean[] assignments, String queryname)
	{
		Converters.csvBuffer csvoutput = null;
		Converters.xmlBuffer xmloutput = null;
		
		String format = req.getParameter("format");
		boolean csvformat = format.compareToIgnoreCase("csv") == 0;

		//generate output buffer
		if(csvformat)
			csvoutput = new Converters.csvBuffer();
		else
			xmloutput = new Converters.xmlBuffer();

		//create an aircraft tag section for each alias
		if(csvformat)
			AddCSVJobItems(csvoutput, assignments);
		else
			AddXMLJobItems(xmloutput, assignments);					
		
		if(csvformat)
			return csvoutput.toString();
		else
		{
			String xsd = GetXsdByQuery(queryname);
			return GetXMLHeader() + "<" + queryname + " total=\"" + assignments.length + "\"" + xsd + ">\n" + xmloutput.toString() + "</" + queryname + ">\n";		
		}
	}

	private String ProcessFacilities(HttpServletRequest req, FboFacilityBean[] facs, String queryname)
	{
		Converters.csvBuffer csvoutput = null;
		Converters.xmlBuffer xmloutput = null;
		
		String format = req.getParameter("format");
		boolean csvformat = format.compareToIgnoreCase("csv") == 0;

		//generate output buffer
		if(csvformat)
			csvoutput = new Converters.csvBuffer();
		else
			xmloutput = new Converters.xmlBuffer();

		//create an aircraft tag section for each alias
		if(csvformat)
			AddCSVFacilityItems(csvoutput, facs);
		else
			AddXMLFacilityItems(xmloutput, facs);
		
		if(csvformat)
			return csvoutput.toString();
		else
		{
			String xsd = GetXsdByQuery(queryname);
			return GetXMLHeader() + "<FacilityItems" + " total=\"" + facs.length + "\"" + xsd + ">\n" + xmloutput.toString() + "</FacilityItems>\n";		
		}
	}

	private String ProcessFbos(HttpServletRequest req, FboBean[] fbos, String queryname)
	{
		Converters.csvBuffer csvoutput = null;
		Converters.xmlBuffer xmloutput = null;
		
		String format = req.getParameter("format");
		boolean csvformat = format.compareToIgnoreCase("csv") == 0;

		//generate output buffer
		if(csvformat)
			csvoutput = new Converters.csvBuffer();
		else
			xmloutput = new Converters.xmlBuffer();

		//create an aircraft tag section for each alias
		if(csvformat)
			AddCSVFboItems(csvoutput, fbos, true);
		else
			AddXMLFboItems(xmloutput, fbos, true);					
		
		if(csvformat)
			return csvoutput.toString();
		else
		{
			String xsd = GetXsdByQuery(queryname);
			return GetXMLHeader() + "<FboItems total=\"" + fbos.length + "\"" + xsd + ">\n" + xmloutput.toString() + "</FboItems>\n";		
		}
	}

	private String ProcessAircraft(HttpServletRequest req, AircraftBean[] aircraft, String queryname)
	{
		Converters.csvBuffer csvoutput = null;
		Converters.xmlBuffer xmloutput = null;
		
		String format = req.getParameter("format");
		boolean csvformat = format.compareToIgnoreCase("csv") == 0;

		//generate output buffer
		if(csvformat)
			csvoutput = new Converters.csvBuffer();
		else
			xmloutput = new Converters.xmlBuffer();

		//create an aircraft tag section for each alias
		if(csvformat)
			AddCSVAircraftItems(csvoutput, aircraft);
		else
			AddXMLAircraftItems(xmloutput, aircraft);					
		
		if(csvformat)
			return csvoutput.toString();
		else
		{
			String xsd = GetXsdByQuery(queryname);
			return GetXMLHeader() + "<AircraftItems query=\"" + queryname + "\" total=\"" + aircraft.length + "\" " + xsd + ">\n" + xmloutput.toString() + "</AircraftItems>";
		}
	}
	
	String GetXMLHeader()
	{
		return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";
	}
	
	String GetXsdByQuery(String query)
	{
		if( query.equalsIgnoreCase("AircraftConfigs"))
		{
			return "\nxmlns=\""+ XSDURL + "\"\n"
			+ "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
			+ "xmlns:schemaLocation=\"" + XSDURL + "/static/datafeed_aircraftconfigs.xsd\"\n";
		}

		if( query.equalsIgnoreCase("AircraftAliases"))
		{
			return "\nxmlns=\""+ XSDURL + "\"\n"
			+ "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
			+ "xmlns:schemaLocation=\"" + XSDURL + "/static/datafeed_aircraftaliases.xsd\"\n";
		}
		
		if( query.equalsIgnoreCase("AircraftForSale") ||
			query.equalsIgnoreCase("AircraftByMakeModel") ||
			query.equalsIgnoreCase("AircraftByOwnerName") ||
			query.equalsIgnoreCase("AircraftByRegistration") ||	
			query.equalsIgnoreCase("AircraftByKey") ||
			query.equalsIgnoreCase("IcaoAircraft"))
		{
			return "\nxmlns=\""+ XSDURL + "\"\n"
				+ "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
				+ "xmlns:schemaLocation=\"" + XSDURL + "/static/datafeed_aircraft.xsd\"\n";
		}

		if( query.equalsIgnoreCase("Assignments"))
		{
			return "\nxmlns=\""+ XSDURL + "\"\n"
			+ "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
			+ "xmlns:schemaLocation=\"" + XSDURL + "/static/datafeed_assignments.xsd\"\n";
		}
		
		if( query.equalsIgnoreCase("Commodities"))
		{
			return "\nxmlns=\""+ XSDURL + "\"\n"
			+ "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
			+ "xmlns:schemaLocation=\"" + XSDURL + "/static/datafeed_commodities.xsd\"\n";
		}
		
		if( query.equalsIgnoreCase("Facilities")) 
		{
			return "\nxmlns=\""+ XSDURL + "\"\n"
				+ "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
				+ "xmlns:schemaLocation=\"" + XSDURL + "/static/datafeed_facilities.xsd\"\n";
		}

		if( query.equalsIgnoreCase("Fbos") ||
			query.equalsIgnoreCase("FbosForSale") ||
			query.equalsIgnoreCase("IcaoFbos"))
		{
			return "\nxmlns=\""+ XSDURL + "\"\n"
				+ "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
				+ "xmlns:schemaLocation=\"" + XSDURL + "/static/datafeed_fbos.xsd\"\n";
		}

		if( query.equalsIgnoreCase("FboMonthlySummary"))
		{
			return "\nxmlns=\""+ XSDURL + "\"\n"
			+ "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
			+ "xmlns:schemaLocation=\"" + XSDURL + "/static/datafeed_fbomonthlysummary.xsd\"\n";
		}
		
		if( query.equalsIgnoreCase("FlightLogsByMonthYear") ||
			query.equalsIgnoreCase("FlightLogsFromId")) 
		{
			return "\nxmlns=\""+ XSDURL + "\"\n"
			+ "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
			+ "xmlns:schemaLocation=\"" + XSDURL + "/static/datafeed_FlightLogs.xsd\"\n";
		}
		
		if( query.equalsIgnoreCase("IcaoJobsTo") ||
			query.equalsIgnoreCase("IcaoJobsFrom")) 
		{
			return "\nxmlns=\""+ XSDURL + "\"\n"
			+ "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
			+ "xmlns:schemaLocation=\"" + XSDURL + "/static/datafeed_jobs.xsd\"\n";
		}
			
		if( query.equalsIgnoreCase("Members"))
		{
			return "\nxmlns=\""+ XSDURL + "\"\n"
			+ "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
			+ "xmlns:schemaLocation=\"" + XSDURL + "/static/datafeed_members.xsd\"\n";
		}
		
		if( query.equalsIgnoreCase("PaymentsByMonthYear") ||
			query.equalsIgnoreCase("PaymentsFromId"))
		{
			return "\nxmlns=\""+ XSDURL + "\"\n"
				+ "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
				+ "xmlns:schemaLocation=\"" + XSDURL + "/static/datafeed_payments.xsd\"\n";
		}

		if( query.equalsIgnoreCase("Statistics"))
		{
			return "\nxmlns=\""+ XSDURL + "\"\n"
				+ "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
				+ "xmlns:schemaLocation=\"" + XSDURL + "/static/datafeed_statistics.xsd\"\n";
		}

		return "";
	}

	void AddCSVFboPaymentSummaryItem(Converters.csvBuffer buffer, UserBean account, FboBean fbo, String icao, int month, int year)
	{
		if(buffer.isHeaderEmpty())
		{
			buffer.appendHeaderItem("Owner");
			buffer.appendHeaderItem("ICAO");
			buffer.appendHeaderItem("Month");
			buffer.appendHeaderItem("Year");
			buffer.appendHeaderItem("Assignment_Rental_Expenses");
			buffer.appendHeaderItem("Assignment_Income");
			buffer.appendHeaderItem("Assignment_Expenses");
			buffer.appendHeaderItem("Assignment_Pilot_Fees");
			buffer.appendHeaderItem("Assignment_Additional_Crew_Fees");
			buffer.appendHeaderItem("Assignment_Ground_Crew_Fees");
			buffer.appendHeaderItem("Assignment_Booking_Fees");
			buffer.appendHeaderItem("Aircraft_Ops_Rental_Income");
			buffer.appendHeaderItem("Aircraft_Ops_Refueling_100LL");
			buffer.appendHeaderItem("Aircraft_Ops_Refueling_JetA");
			buffer.appendHeaderItem("Aircraft_Ops_Landing_Fees");
			buffer.appendHeaderItem("Aircraft_Ops_Expenses_for_Maintenance");
			buffer.appendHeaderItem("Aircraft_Ops_Equipment_Installation");			
			buffer.appendHeaderItem("Aircraft_Sold");
			buffer.appendHeaderItem("Aircraft_Bought");					
			buffer.appendHeaderItem("Fbo_Ops_Refueling_100LL");
			buffer.appendHeaderItem("Fbo_Ops_Refueling_JetA");
			buffer.appendHeaderItem("Fbo_Ops_Ground_Crew_Fees");
			buffer.appendHeaderItem("Fbo_Ops_Repairshop_Income");
			buffer.appendHeaderItem("Fbo_Ops_Repairshop_Expenses");
			buffer.appendHeaderItem("Fbo_Ops_Equipment_Installation");
			buffer.appendHeaderItem("Fbo_Ops_Equipment_Expenses");					
			buffer.appendHeaderItem("PT_Rent_Income");
			buffer.appendHeaderItem("PT_Rent_Expenses");					
			buffer.appendHeaderItem("FBO_Sold");
			buffer.appendHeaderItem("FBO_Bought");			
			buffer.appendHeaderItem("Goods_Bought_Wholesale_100LL");
			buffer.appendHeaderItem("Goods_Bought_Wholesale_JetA");
			buffer.appendHeaderItem("Goods_Bought_Building_Materials");
			buffer.appendHeaderItem("Goods_Bought_Supplies");			
			buffer.appendHeaderItem("Goods_Sold_Wholesale_100LL");
			buffer.appendHeaderItem("Goods_Sold_Wholesale_JetA");
			buffer.appendHeaderItem("Goods_Sold_Building_Materials");
			buffer.appendHeaderItem("Goods_Sold_Supplies");					
			buffer.appendHeaderItem("Group_Payments");
			buffer.appendHeaderItem("Group_Deletion");					
			buffer.appendHeaderItem("Net_Total");
			buffer.appendHeaderItem("Current_Ops");
			buffer.appendHeaderItem("Avg_Ops");
		}
		
		double[][] statement = data.getStatement(new GregorianCalendar(year, month-1, 1), account.getId(), fbo.getId(), null, account.getShowPaymentsToSelf());
		int[] ops = data.getAirportOperationsPerMonth(icao);
		
		buffer.append(Converters.XMLHelper.protectSpecialCharacters(account.getName()));
		buffer.append(icao.toUpperCase());
		buffer.append(month);
		buffer.append(year);
		buffer.appendMoney(statement[PaymentBean.RENTAL][1]);
		buffer.appendMoney(statement[PaymentBean.ASSIGNMENT][0]);
		buffer.appendMoney(statement[PaymentBean.ASSIGNMENT][1]);
		buffer.appendMoney(statement[PaymentBean.PILOT_FEE][0] + statement[PaymentBean.PILOT_FEE][1]);
		buffer.appendMoney(statement[PaymentBean.CREW_FEE][0] + statement[PaymentBean.CREW_FEE][1]);
		buffer.appendMoney(statement[PaymentBean.FBO_ASSIGNMENT_FEE][1]);
		buffer.appendMoney(statement[PaymentBean.MULTIPLE_PT_TAX][1]);
				
		buffer.appendMoney(statement[PaymentBean.RENTAL][0]);
		buffer.appendMoney(statement[PaymentBean.REASON_REFUEL][1]);
		buffer.appendMoney(statement[PaymentBean.REASON_REFUEL_JETA][1]);
		buffer.appendMoney(statement[PaymentBean.LANDING_FEE][0] + statement[PaymentBean.LANDING_FEE][1]);
		buffer.appendMoney(statement[PaymentBean.MAINTENANCE][1]);
		buffer.appendMoney(statement[PaymentBean.EQUIPMENT][1]);
		
		buffer.appendMoney(statement[PaymentBean.AIRCRAFT_SALE][0]);
		buffer.appendMoney(statement[PaymentBean.AIRCRAFT_SALE][1]);
				
		buffer.appendMoney(statement[PaymentBean.REASON_REFUEL][0]);
		buffer.appendMoney(statement[PaymentBean.REASON_REFUEL_JETA][0]);
		buffer.appendMoney(statement[PaymentBean.FBO_ASSIGNMENT_FEE][0]);
		buffer.appendMoney(statement[PaymentBean.MAINTENANCE][0]);
		buffer.appendMoney(statement[PaymentBean.MAINTENANCE_FBO_COST][0] + statement[PaymentBean.MAINTENANCE_FBO_COST][1]);
		buffer.appendMoney(statement[PaymentBean.EQUIPMENT][0]);
		buffer.appendMoney(statement[PaymentBean.EQUIPMENT_FBO_COST][0] + statement[PaymentBean.EQUIPMENT_FBO_COST][1]);
				
		buffer.appendMoney(statement[PaymentBean.FBO_FACILITY_RENT][0]);
		buffer.appendMoney(statement[PaymentBean.FBO_FACILITY_RENT][1]);
				
		buffer.appendMoney(statement[PaymentBean.FBO_SALE][0]);
		buffer.appendMoney(statement[PaymentBean.FBO_SALE][1]);
		
		buffer.appendMoney(statement[PaymentBean.SALE_GOODS_FUEL][1]);
		buffer.appendMoney(statement[PaymentBean.SALE_GOODS_JETA][1]);
		buffer.appendMoney(statement[PaymentBean.SALE_GOODS_BUILDING_MATERIALS][1]);
		buffer.appendMoney(statement[PaymentBean.SALE_GOODS_SUPPLIES][1]);
		
		buffer.appendMoney(statement[PaymentBean.SALE_GOODS_FUEL][0]);
		buffer.appendMoney(statement[PaymentBean.SALE_GOODS_JETA][0]);
		buffer.appendMoney(statement[PaymentBean.SALE_GOODS_BUILDING_MATERIALS][0]);
		buffer.appendMoney(statement[PaymentBean.SALE_GOODS_SUPPLIES][0]);
				
		buffer.appendMoney(statement[PaymentBean.GROUP_PAYMENT][0] + statement[PaymentBean.GROUP_PAYMENT][1]);
		buffer.appendMoney(statement[PaymentBean.GROUP_DELETION][0] + statement[PaymentBean.GROUP_DELETION][1]);
				
		buffer.appendMoney(statement[0][0]);
		buffer.append(ops[0]);
		buffer.append(ops[1]);
		buffer.newrow();
	}
	
	void AddXMLFboPaymentSummaryItem(Converters.xmlBuffer buffer, UserBean account, FboBean fbo, String icao, int month, int year)
	{
		double[][] statement = data.getStatement(new GregorianCalendar(year, month-1, 1), account.getId(), fbo.getId(), null, account.getShowPaymentsToSelf());
		int[] ops = data.getAirportOperationsPerMonth(icao);
		
		buffer.append("<FboMonthlySummary>\n");
		buffer.append("Owner", Converters.XMLHelper.protectSpecialCharacters(account.getName()));
		buffer.append("ICAO", icao.toUpperCase());
		buffer.append("Month", month);
		buffer.append("Year", year);
		buffer.appendMoney("Assignment_Rental_Expenses", statement[PaymentBean.RENTAL][1]);
		buffer.appendMoney("Assignment_Income", statement[PaymentBean.ASSIGNMENT][0]);
		buffer.appendMoney("Assignment_Expenses", statement[PaymentBean.ASSIGNMENT][1]);
		buffer.appendMoney("Assgiment_Pilot_Fees", statement[PaymentBean.PILOT_FEE][0] + statement[PaymentBean.PILOT_FEE][1]);
		buffer.appendMoney("Assgiment_Additional_Crew_Fees", statement[PaymentBean.CREW_FEE][0] + statement[PaymentBean.CREW_FEE][1]);
		buffer.appendMoney("Assgiment_Ground_Crew_Fees", statement[PaymentBean.FBO_ASSIGNMENT_FEE][1]);
		buffer.appendMoney("Assgiment_Booking_Fees", statement[PaymentBean.MULTIPLE_PT_TAX][1]);
				
		buffer.appendMoney("Aircraft_Ops_Rental_Income", statement[PaymentBean.RENTAL][0]);
		buffer.appendMoney("Aircraft_Ops_Refueling_100LL", statement[PaymentBean.REASON_REFUEL][1]);
		buffer.appendMoney("Aircraft_Ops_Refueling_JetA", statement[PaymentBean.REASON_REFUEL_JETA][1]);
		buffer.appendMoney("Aircraft_Ops_Landing_Fees", statement[PaymentBean.LANDING_FEE][0] + statement[PaymentBean.LANDING_FEE][1]);
		buffer.appendMoney("Aircraft_Ops_Expenses_for_Maintenance", statement[PaymentBean.MAINTENANCE][1]);
		buffer.appendMoney("Aircraft_Ops_Equipment_Installation", statement[PaymentBean.EQUIPMENT][1]);
		
		buffer.appendMoney("Aircraft_Sold", statement[PaymentBean.AIRCRAFT_SALE][0]);
		buffer.appendMoney("Aircraft_Bought", statement[PaymentBean.AIRCRAFT_SALE][1]);
				
		buffer.appendMoney("Fbo_Ops_Refueling_100LL", statement[PaymentBean.REASON_REFUEL][0]);
		buffer.appendMoney("Fbo_Ops_Refueling_JetA", statement[PaymentBean.REASON_REFUEL_JETA][0]);
		buffer.appendMoney("Fbo_Ops_Ground_Crew_Fees", statement[PaymentBean.FBO_ASSIGNMENT_FEE][0]);
		buffer.appendMoney("Fbo_Ops_Repairshop_Income", statement[PaymentBean.MAINTENANCE][0]);
		buffer.appendMoney("Fbo_Ops_Repairshop_Expenses", statement[PaymentBean.MAINTENANCE_FBO_COST][0] + statement[PaymentBean.MAINTENANCE_FBO_COST][1]);
		buffer.appendMoney("Fbo_Ops_Equipment_Installation", statement[PaymentBean.EQUIPMENT][0]);
		buffer.appendMoney("Fbo_Ops_Equipment_Expenses", statement[PaymentBean.EQUIPMENT_FBO_COST][0] + statement[PaymentBean.EQUIPMENT_FBO_COST][1]);
				
		buffer.appendMoney("PT_Rent_Income", statement[PaymentBean.FBO_FACILITY_RENT][0]);
		buffer.appendMoney("PT_Rent_Expenses", statement[PaymentBean.FBO_FACILITY_RENT][1]);
				
		buffer.appendMoney("FBO_Sold", statement[PaymentBean.FBO_SALE][0]);
		buffer.appendMoney("FBO_Bought", statement[PaymentBean.FBO_SALE][1]);
		
		buffer.appendMoney("Goods_Bought_Wholesale_100LL", statement[PaymentBean.SALE_GOODS_FUEL][1]);
		buffer.appendMoney("Goods_Bought_Wholesale_JetA", statement[PaymentBean.SALE_GOODS_JETA][1]);
		buffer.appendMoney("Goods_Bought_Building_Materials", statement[PaymentBean.SALE_GOODS_BUILDING_MATERIALS][1]);
		buffer.appendMoney("Goods_Bought_Supplies", statement[PaymentBean.SALE_GOODS_SUPPLIES][1]);
		
		buffer.appendMoney("Goods_Sold_Wholesale_100LL", statement[PaymentBean.SALE_GOODS_FUEL][0]);
		buffer.appendMoney("Goods_Sold_Wholesale_JetA", statement[PaymentBean.SALE_GOODS_JETA][0]);
		buffer.appendMoney("Goods_Sold_Building_Materials", statement[PaymentBean.SALE_GOODS_BUILDING_MATERIALS][0]);
		buffer.appendMoney("Goods_Sold_Supplies", statement[PaymentBean.SALE_GOODS_SUPPLIES][0]);
				
		buffer.appendMoney("Group_Payments", statement[PaymentBean.GROUP_PAYMENT][0] + statement[PaymentBean.GROUP_PAYMENT][1]);
		buffer.appendMoney("Group_Deletion", statement[PaymentBean.GROUP_DELETION][0] + statement[PaymentBean.GROUP_DELETION][1]);
				
		buffer.appendMoney("Net_Total", statement[0][0]);
		buffer.append("Current_Ops", ops[0]);
		buffer.append("Avg_Ops", ops[1]);
		buffer.append("</FboMonthlySummary>\n");
	}
	
	void AddCSVAssignmentItems(Converters.csvBuffer buffer, AssignmentBean[] assignments, int id) throws DataError
	{
		if(buffer.isHeaderEmpty())
		{
	        buffer.appendHeaderItem("Id");        
	        buffer.appendHeaderItem("Status");        
			buffer.appendHeaderItem("Location");        
			buffer.appendHeaderItem("From");
			buffer.appendHeaderItem("Destination");
			//buffer.appendHeaderItem("NM");
			//buffer.appendHeaderItem("Bearing");
			buffer.appendHeaderItem("Assignment");
			buffer.appendHeaderItem("Amount");
			buffer.appendHeaderItem("Units");
			buffer.appendHeaderItem("Pay");
			buffer.appendHeaderItem("PilotFee");		
			buffer.appendHeaderItem("Expires");		
			buffer.appendHeaderItem("ExpireDateTime");		
			buffer.appendHeaderItem("Locked");			
			buffer.appendHeaderItem("Comment");			
		}

        for (AssignmentBean assignment : assignments)
        {
            Map<String, Integer> flightsMap = new HashMap<>();
            AircraftBean[] aircraft = data.getAircraftForUser(id);
            if (aircraft.length > 0)
            {
                flightsMap = data.getMyFlightInfo(aircraft[0], id);
            }

            UserBean lockedBy = null;
            if (assignment.getUserlock() != 0)
            {
                lockedBy = data.getAccountById(assignment.getUserlock());
            }

            String locked = lockedBy == null ? "-" : lockedBy.getName();

            String status;
            if (assignment.getActive() > 0)
            {
                status = assignment.getActive() == 2 ? "On Hold" : "Enroute";
            }
            else if (flightsMap != null && flightsMap.containsKey((Integer.toString(assignment.getId()))))
            {
                status = "Departing";
            }
            else
            {
                status = "Selected";
            }

            String location;
            location = assignment.getLocation() == null ? "enroute" : assignment.getLocation();

            String noHTMLAssignment = assignment.getSCargo().replaceAll("<.*?>", "");

            String expires;
            expires = assignment.getExpires() == null ? "never" : assignment.getSExpires();

            buffer.append(assignment.getId());
            buffer.append(status);
            buffer.append(location);
            buffer.append(assignment.getFrom());
            buffer.append(assignment.getTo());
            //buffer.append(assignment.getActualDistance(data));
            //buffer.append(Formatters.threeDigits.format(assignment.getActualBearing(data)));
            buffer.append(noHTMLAssignment);
            buffer.append(assignment.getAmount());
            buffer.append(assignment.getSUnits());
            buffer.appendMoney(assignment.calcPay());
            buffer.appendMoney(assignment.getPilotFee());
            buffer.append(expires);
            buffer.append(assignment.getExpires() == null ? "9999/1/1 00:00:00" : assignment.getExpiresGMTDate());
            buffer.append(locked);
            buffer.append(Converters.XMLHelper.protectSpecialCharacters(assignment.getComment()));
            buffer.newrow();
        }
	}

	void AddXMLAssignmentItems(Converters.xmlBuffer buffer, AssignmentBean[] assignments, int id) throws DataError
	{
        for (AssignmentBean assignment : assignments)
        {
            Map<String, Integer> flightsMap = new HashMap<>();
            AircraftBean[] aircraft = data.getAircraftForUser(id);
            if (aircraft.length > 0)
            {
                flightsMap = data.getMyFlightInfo(aircraft[0], id);
            }

            UserBean lockedBy = null;
            if (assignment.getUserlock() != 0)
            {
                lockedBy = data.getAccountById(assignment.getUserlock());
            }
            String locked = lockedBy == null ? "-" : lockedBy.getName();

            String status;
            if (assignment.getActive() > 0)
            {
                status = assignment.getActive() == 2 ? "On Hold" : "Enroute";
            }
            else if (flightsMap != null && flightsMap.containsKey((Integer.toString(assignment.getId()))))
            {
                status = "Departing";
            }
            else
            {
                status = "Selected";
            }

            String location;
            location = assignment.getLocation() == null ? "enroute" : assignment.getLocation();

            String noHTMLAssignment = assignment.getSCargo().replaceAll("<.*?>", "");

            String expires;
            expires = assignment.getExpires() == null ? "never" : assignment.getSExpires();

            buffer.append("<Assignment>\n");
            buffer.append("Id", assignment.getId());
            buffer.append("Status", status);
            buffer.append("Location", location);
            buffer.append("From", assignment.getFrom());
            buffer.append("Destination", assignment.getTo());
            //buffer.append("NM", assignment.getActualDistance(data));
            //buffer.append("Bearing", Formatters.threeDigits.format(assignment.getActualBearing(data)));
            buffer.append("Assignment", noHTMLAssignment);
            buffer.append("Amount", assignment.getAmount());
            buffer.append("Units", assignment.getSUnits());
            buffer.appendMoney("Pay", assignment.calcPay());
            buffer.appendMoney("PilotFee", assignment.getPilotFee());
            buffer.append("Expires", expires);
            buffer.append("ExpireDateTime", assignment.getExpires() == null ? "9999/1/1 00:00:00" : assignment.getExpiresGMTDate());
            buffer.append("Locked", locked);
            buffer.append("Comment", Converters.XMLHelper.protectSpecialCharacters(assignment.getComment()));
            buffer.append("</Assignment>\n");
        }
	}

	void AddCSVFlightLogItems(Converters.csvBuffer buffer, LogBean[] logs)
	{
		if(buffer.isHeaderEmpty())
		{
			buffer.appendHeaderItem("Id");
			buffer.appendHeaderItem("Type");			
			buffer.appendHeaderItem("Time");
			buffer.appendHeaderItem("Distance");
			buffer.appendHeaderItem("Pilot");
			buffer.appendHeaderItem("Aircraft");
			buffer.appendHeaderItem("MakeModel");
			buffer.appendHeaderItem("From");
			buffer.appendHeaderItem("To");
			buffer.appendHeaderItem("TotalEngineTime");
			buffer.appendHeaderItem("FlightTime");
			buffer.appendHeaderItem("GroupName");		
			buffer.appendHeaderItem("Income");
			buffer.appendHeaderItem("PilotFee");
			buffer.appendHeaderItem("CrewCost");
			buffer.appendHeaderItem("BookingFee");
			buffer.appendHeaderItem("Bonus");
			buffer.appendHeaderItem("FuelCost");
			buffer.appendHeaderItem("GCF");
			buffer.appendHeaderItem("RentalPrice");
			buffer.appendHeaderItem("RentalType");
			buffer.appendHeaderItem("RentalUnits");
			buffer.appendHeaderItem("RentalCost");
		}

        for (LogBean log : logs)
        {
            String type = "None";
            AircraftBean[] aircraft = data.getAircraftByRegistration(log.getAircraft());
            if (aircraft != null && aircraft.length > 0)
            {
                type = aircraft[0].getMakeModel();
            }

            String groupName = "";
            if (log.getGroupId() > 0)
            {
                UserBean[] group = data.getGroupById(log.getGroupId());
                if (group.length > 0)
                {
                    groupName = Converters.XMLHelper.protectSpecialCharacters(group[0].getName());
                }
            }

            String rentalType;
            if (log.getAccounting() == 1)
            {
                rentalType = "tacho";
            }
            else
            {
                rentalType = "hobbs";
            }

            String rentalUnits;
            if (log.getAccounting() == 1)
            {
                rentalUnits = "" + log.getFlightEngineTicks();
            }
            else
            {
                rentalUnits = TimeToHrsMins(log.getFlightEngineTime());
            }

            String totalenginetime = TimeToHrsMins(log.getTotalEngineTime());
            String totalflighttime = TimeToHrsMins(log.getFlightEngineTime());

            buffer.append(log.getId());
            buffer.append(log.getType());
            buffer.append(Formatters.dateDataFeed.format(log.getTime()));
            buffer.append(log.getDistance());
            buffer.append(log.getUser() == null ? "" : log.getUser());
            buffer.append(log.getAircraft());
            buffer.append(type);
            buffer.append(log.getFrom() == null ? "" : log.getFrom());
            buffer.append(log.getTo() == null ? "" : log.getTo());
            buffer.append(totalenginetime);
            buffer.append(totalflighttime);
            buffer.append(groupName);
            buffer.appendMoney(log.getIncome());
            buffer.appendMoney(log.getPilotFee());
            buffer.appendMoney(log.getCrewCost());
            buffer.appendMoney(log.getmptTax());
            buffer.appendMoney(log.getBonus());
            buffer.appendMoney(log.getFuelCost());
            buffer.appendMoney(log.getFboAssignmentFee());
            buffer.appendMoney(log.getRentalPrice());
            buffer.append(rentalType);
            buffer.append(rentalUnits);
            buffer.appendMoney(log.getRentalCost());
            buffer.newrow();
        }
	}
	
	void AddXMLFlightLogItems(Converters.xmlBuffer buffer, LogBean[] logs)
	{
        for (LogBean log : logs)
        {
            String type;
            try
            {
                type = data.getAircraftByRegistration(log.getAircraft())[0].getMakeModel();
            }
            catch (ArrayIndexOutOfBoundsException e)
            {
                type = "None";
            }

            String groupName = "";
            if (log.getGroupId() > 0)
            {
                UserBean[] group = data.getGroupById(log.getGroupId());
                if (group.length > 0)
                {
                    groupName = Converters.XMLHelper.protectSpecialCharacters(group[0].getName());
                }
            }

            String rentalType;
            if (log.getAccounting() == 1)
            {
                rentalType = "tacho";
            }
            else
            {
                rentalType = "hobbs";
            }

            String rentalUnits;
            if (log.getAccounting() == 1)
            {
                rentalUnits = "" + log.getFlightEngineTicks();
            }
            else
            {
                rentalUnits = TimeToHrsMins(log.getFlightEngineTime());
            }

            String totalenginetime = TimeToHrsMins(log.getTotalEngineTime());
            String totalflighttime = TimeToHrsMins(log.getFlightEngineTime());

            buffer.append("<FlightLog>\n");
            buffer.append("Id", log.getId());
            buffer.append("Type", log.getType());
            buffer.append("Time", Formatters.dateDataFeed.format(log.getTime()));
            buffer.append("Distance", log.getDistance());
            buffer.append("Pilot", log.getUser());
            buffer.append("Aircraft", log.getAircraft());
            buffer.append("MakeModel", type);
            buffer.append("From", log.getFrom() == null ? "" : log.getFrom());
            buffer.append("To", log.getFrom() == null ? "" : log.getTo());
            buffer.append("TotalEngineTime", totalenginetime);
            buffer.append("FlightTime", totalflighttime);
            buffer.append("GroupName", groupName);
            buffer.appendMoney("Income", log.getIncome());
            buffer.appendMoney("PilotFee", log.getPilotFee());
            buffer.appendMoney("CrewCost", log.getCrewCost());
            buffer.appendMoney("BookingFee", log.getmptTax());
            buffer.appendMoney("Bonus", log.getBonus());
            buffer.appendMoney("FuelCost", log.getFuelCost());
            buffer.appendMoney("GCF", log.getFboAssignmentFee());
            buffer.appendMoney("RentalPrice", log.getRentalPrice());
            buffer.append("RentalType", rentalType);
            buffer.append("RentalUnits", rentalUnits);
            buffer.appendMoney("RentalCost", log.getRentalCost());
            buffer.append("</FlightLog>\n");
        }
	}
	
	void AddCSVPaymentItems(Converters.csvBuffer buffer, PaymentBean[] payments)
	{
		if(buffer.isHeaderEmpty())
		{
			buffer.appendHeaderItem("Id");
			buffer.appendHeaderItem("Date");
			buffer.appendHeaderItem("To");
			buffer.appendHeaderItem("From");
			buffer.appendHeaderItem("Amount");
			buffer.appendHeaderItem("Reason");
			buffer.appendHeaderItem("Fbo");
			buffer.appendHeaderItem("Location");
			buffer.appendHeaderItem("Aircraft");
			buffer.appendHeaderItem("Comment");
		}

        for (PaymentBean payment : payments)
        {
            String fboname = "N/A";
            if (payment.getFboId() != -1)
            {
                FboBean fbobean = data.getFbo(payment.getFboId());
                if (fbobean != null)
                {
                    fboname = fbobean.getLocation() + " " + fbobean.getName();
                }
            }

            String toname = data.getAccountNameById(payment.getUser());
            if (toname == null)
            {
                toname = "Unknown";
            }

            String fromname = data.getAccountNameById(payment.getOtherParty());
            if (fromname == null)
            {
                fromname = "Unknown";
            }

            buffer.append(payment.getId());
            buffer.append(Formatters.dateDataFeed.format(payment.getTime()));
            buffer.append(Converters.XMLHelper.protectSpecialCharacters(toname));
            buffer.append(Converters.XMLHelper.protectSpecialCharacters(fromname));
            buffer.appendMoney(payment.getAmount());
            buffer.append(payment.getSReason());
            buffer.append(Converters.XMLHelper.protectSpecialCharacters(fboname));
            buffer.append(payment.getLocation() == null ? "" : payment.getLocation());
            buffer.append(payment.getAircraft() == null ? "" : payment.getAircraft());
            buffer.append(payment.getComment() == null ? "" : payment.getComment()); //Converters.XMLHelper.protectSpecialCharacters(payment.getComment()));
            buffer.newrow();
        }
	}
	
	void AddXMLPaymentItems(Converters.xmlBuffer buffer, PaymentBean[] payments)
	{
        for (PaymentBean payment : payments)
        {
            String fboname = "N/A";
            if (payment.getFboId() != -1)
            {
                FboBean fbobean = data.getFbo(payment.getFboId());
                if (fbobean != null)
                {
                    fboname = fbobean.getLocation() + " " + fbobean.getName();
                }
            }

            String toname = data.getAccountNameById(payment.getUser());
            if (toname == null)
            {
                toname = "Unknown";
            }

            String fromname = data.getAccountNameById(payment.getOtherParty());
            if (fromname == null)
            {
                fromname = "Unknown";
            }

            buffer.append("<Payment>\n");
            buffer.append("Id", payment.getId());
            buffer.append("Date", Formatters.dateDataFeed.format(payment.getTime()));
            buffer.append("To", Converters.XMLHelper.protectSpecialCharacters(toname));
            buffer.append("From", Converters.XMLHelper.protectSpecialCharacters(fromname));
            buffer.appendMoney("Amount", payment.getAmount());
            buffer.append("Reason", payment.getSReason());
            buffer.append("Fbo", Converters.XMLHelper.protectSpecialCharacters(fboname));
            buffer.append("Location", payment.getLocation());
            buffer.append("Aircraft", payment.getAircraft());
            buffer.append("Comment", Converters.XMLHelper.protectSpecialCharacters(payment.getComment()));
            buffer.append("</Payment>\n");
        }
	}

	private void AddCSVAircraftItems(Converters.csvBuffer buffer, AircraftBean[] aircraftlist)
	{
		//fill in the header if empty
		if(buffer.isHeaderEmpty())
		{
			buffer.appendHeaderItem("MakeModel");
			buffer.appendHeaderItem("Registration");
			buffer.appendHeaderItem("Owner");
			buffer.appendHeaderItem("Location");
			buffer.appendHeaderItem("LocationName");
			buffer.appendHeaderItem("Home");
			buffer.appendHeaderItem("SalePrice");
			buffer.appendHeaderItem("SellbackPrice");
			buffer.appendHeaderItem("Equipment");
			buffer.appendHeaderItem("RentalDry");
			buffer.appendHeaderItem("RentalWet");
			buffer.appendHeaderItem("RentalType");
			buffer.appendHeaderItem("Bonus");
			buffer.appendHeaderItem("RentalTime");
			buffer.appendHeaderItem("RentedBy");			
			buffer.appendHeaderItem("PctFuel");
			buffer.appendHeaderItem("NeedsRepair");
			buffer.appendHeaderItem("AirframeTime");
			buffer.appendHeaderItem("EngineTime");
			buffer.appendHeaderItem("TimeLast100hr");			
		}

        for (AircraftBean aircraft : aircraftlist)
        {
            //setup our needed variables
            String loc;
            String locname;
            ModelBean modelBean = data.getModelById(aircraft.getModelId())[0];
            AirportBean location = data.getAirport(aircraft.getLocation());

            if (aircraft.getLocation() == null)
            {
                loc = "In Flight";
                locname = "In Flight";
            }
            else
            {
                loc = aircraft.getLocation();
                locname = location.getTitle();
            }

            //get the aircraft owner, stolen from AircraftLog.jsp
            String owner = "Bank of FSE";
            if (aircraft.getOwner() != 0)
            {
                UserBean uOwner = data.getAccountById(aircraft.getOwner());
                if (uOwner != null)
                {
                    if (uOwner.isGroup())
                    {
                        UserBean gOwner = data.getAccountById(data.accountUltimateGroupOwner(uOwner.getId()));
                        if (gOwner != null)
                        {
                            owner = uOwner.getName() + " (" + gOwner.getName() + ")";
                        }
                        else
                        {
                            owner = uOwner.getName();
                        }
                    }
                    else
                    {
                        owner = uOwner.getName();
                    }
                }
            }

            String userlockname;
            if (aircraft.getUserLock() > 0)
            {
                UserBean lockuser = data.getAccountById(aircraft.getUserLock());
                userlockname = lockuser.getName();
            }
            else
            {
                userlockname = "Not rented.";
            }

            buffer.append(aircraft.getMakeModel());
            buffer.append(aircraft.getRegistration());
            buffer.append(Converters.XMLHelper.protectSpecialCharacters(owner));
            buffer.append(loc);
            buffer.append(Converters.XMLHelper.protectSpecialCharacters(locname));
            buffer.append(aircraft.getHome());
            buffer.appendMoney(aircraft.getSellPrice());
            buffer.appendMoney(aircraft.getMinimumPrice(modelBean));
            buffer.append(aircraft.getSEquipment());
            buffer.appendMoney(aircraft.getRentalPriceDry());
            buffer.appendMoney(aircraft.getRentalPriceWet());
            buffer.append(aircraft.getSAccounting());
            buffer.appendMoney(aircraft.getBonus());
            buffer.append(aircraft.getMaxRentTime());
            buffer.append(userlockname);
            buffer.append(Formatters.twoDecimals.format(aircraft.getTotalFuel() / aircraft.getTotalCapacity()));
            buffer.append(aircraft.getCanFlyAssignments(modelBean) ? 0 : 1);
            buffer.append(aircraft.getAirframeHoursString());
            buffer.append(aircraft.getEngineHoursString());
            buffer.append(aircraft.getHoursSinceLastCheckString());
            buffer.newrow();
        }
	}
	
	private void AddXMLAircraftItems(Converters.xmlBuffer buffer, AircraftBean[] aircraftlist)
	{
        for (AircraftBean aircraft : aircraftlist)
        {
            //setup our needed variables
            String loc;
            String locname;
            ModelBean modelBean = data.getModelById(aircraft.getModelId())[0];
            AirportBean location = data.getAirport(aircraft.getLocation());

            if (aircraft.getLocation() == null)
            {
                loc = "In Flight";
                locname = "In Flight";
            }
            else
            {
                loc = aircraft.getLocation();
                locname = location.getTitle();
            }

            //get the aircraft owner, stolen from AircraftLog.jsp
            String owner = "Bank of FSE";
            if (aircraft.getOwner() != 0)
            {
                UserBean uOwner = data.getAccountById(aircraft.getOwner());
                if (uOwner != null)
                {
                    if (uOwner.isGroup())
                    {
                        UserBean gOwner = data.getAccountById(data.accountUltimateGroupOwner(uOwner.getId()));
                        if (gOwner != null)
                        {
                            owner = uOwner.getName() + " (" + gOwner.getName() + ")";
                        }
                        else
                        {
                            owner = uOwner.getName();
                        }
                    }
                    else
                    {
                        owner = uOwner.getName();
                    }
                }
            }

            String userlockname;
            if (aircraft.getUserLock() > 0)
            {
                UserBean lockuser = data.getAccountById(aircraft.getUserLock());
                userlockname = lockuser.getName();
            }
            else
            {
                userlockname = "Not rented.";
            }

            buffer.append("<Aircraft>\n");
            buffer.append("MakeModel", aircraft.getMakeModel());
            buffer.append("Registration", aircraft.getRegistration());
            buffer.append("Owner", Converters.XMLHelper.protectSpecialCharacters(owner));
            buffer.append("Location", loc);
            buffer.append("LocationName", Converters.XMLHelper.protectSpecialCharacters(locname));
            buffer.append("Home", aircraft.getHome());
            buffer.appendMoney("SalePrice", aircraft.getSellPrice());
            buffer.appendMoney("SellbackPrice", aircraft.getMinimumPrice(modelBean));
            buffer.append("Equipment", aircraft.getSEquipment());
            buffer.appendMoney("RentalDry", aircraft.getRentalPriceDry());
            buffer.appendMoney("RentalWet", aircraft.getRentalPriceWet());
            buffer.append("RentalType", aircraft.getSAccounting());
            buffer.append("Bonus", aircraft.getBonus());
            buffer.append("RentalTime", aircraft.getMaxRentTime());
            buffer.append("RentedBy", userlockname);
            buffer.append("FuelPct", Formatters.twoDecimals.format(aircraft.getTotalFuel() / aircraft.getTotalCapacity()));
            buffer.append("NeedsRepair", aircraft.getCanFlyAssignments(modelBean) ? 0 : 1);
            buffer.append("AirframeTime", aircraft.getAirframeHoursString());
            buffer.append("EngineTime", aircraft.getEngineHoursString());
            buffer.append("TimeLast100hr", aircraft.getHoursSinceLastCheckString());
            buffer.append("</Aircraft>\n");
        }
	}	
	
	private void AddCSVSystemFboItem(Converters.csvBuffer buffer, AirportBean airport)
	{
		if(buffer.isHeaderEmpty())
		{			
			buffer.appendHeaderItem("Status");
			buffer.appendHeaderItem("Airport");
			buffer.appendHeaderItem("Name");
			buffer.appendHeaderItem("Owner");
			buffer.appendHeaderItem("Icao");
			buffer.appendHeaderItem("Location");
			buffer.appendHeaderItem("Lots");
			buffer.appendHeaderItem("RepairShop");
			buffer.appendHeaderItem("Gates");
			buffer.appendHeaderItem("GatesRented");
			buffer.appendHeaderItem("Fuel100LL");
			buffer.appendHeaderItem("FuelJetA");
			buffer.appendHeaderItem("BuildingMaterials");
			buffer.appendHeaderItem("Supplies");
			buffer.appendHeaderItem("SuppliesPerDay");
			buffer.appendHeaderItem("SuppliedDays");
			buffer.appendHeaderItem("SellPrice");
		}
		
		buffer.append("Active");	
		buffer.append(airport.getName());
		buffer.append("System");			
		buffer.append("System");			
		buffer.append(airport.getIcao());
		buffer.append(airport.getCity() + ", " + airport.getCountry());
		buffer.append("N/A");
		buffer.append(airport.getSize() >= AircraftMaintenanceBean.REPAIR_AVAILABLE_AIRPORT_SIZE ? "Yes" : "No");
		buffer.append("N/A");
		buffer.append("N/A");
		buffer.append(airport.isAvgas() ? "Unlimited" : "Not Avail");
		buffer.append(airport.isJetA() ? "Unlimited" : "Not Avail");
		buffer.append(airport.getSize() >= AirportBean.MIN_SIZE_BIG ? "Unlimited" : "0");
		buffer.append(airport.getSize() >= AirportBean.MIN_SIZE_BIG ? "Unlimited" : "0");
		buffer.append("N/A");
		buffer.append("N/A");
		buffer.append("N/A");	
		buffer.newrow(); 
	}
	
	private void AddXMLSystemFboItem(Converters.xmlBuffer buffer, AirportBean airport)
	{
		buffer.append("<FBO>\n");
		buffer.append("Status", "Active");	
		buffer.append("Airport", airport.getName());
		buffer.append("Name", "System");			
		buffer.append("Owner", "System");			
		buffer.append("Icao", airport.getIcao());
		buffer.append("Location", airport.getCity() + ", " + airport.getCountry());
		buffer.append("Lots", "N/A");
		buffer.append("RepairShop", airport.getSize() >= AircraftMaintenanceBean.REPAIR_AVAILABLE_AIRPORT_SIZE ? "Yes" : "No");
		buffer.append("Gates", "N/A");
		buffer.append("GatesRented", "N/A");
		buffer.append("Fuel100LL", airport.isAvgas() ? "Unlimited" : "Not Avail");
		buffer.append("FuelJetA", airport.isJetA() ? "Unlimited" : "Not Avail");
		buffer.append("BuildingMaterials", airport.getSize() >= AirportBean.MIN_SIZE_BIG ? "Unlimited" : "0");
		buffer.append("Supplies", airport.getSize() >= AirportBean.MIN_SIZE_BIG ? "Unlimited" : "0");
		buffer.append("SuppliesPerDay", "N/A");
		buffer.append("SuppliedDays", "N/A");
		buffer.append("SellPrice", "N/A");								
		buffer.append("</FBO>\n");
	}
	
	private void AddCSVFacilityItems(Converters.csvBuffer buffer, FboFacilityBean[] facs)
	{
		if(buffer.isHeaderEmpty())
		{			
			buffer.appendHeaderItem("Icao");
			buffer.appendHeaderItem("Location");
			buffer.appendHeaderItem("Carrier");
			buffer.appendHeaderItem("CommodityNames");
			buffer.appendHeaderItem("GatesTotal");
			buffer.appendHeaderItem("GatesRented");
			buffer.appendHeaderItem("JobsPublic");
			buffer.appendHeaderItem("Destinations");
			buffer.appendHeaderItem("Fbo");
			buffer.appendHeaderItem("Status");
		}

        for (FboFacilityBean fac : facs)
        {
            FboBean fbo = data.getFbo(fac.getFboId());
            AirportBean airport = data.getAirport(fac.getLocation());

            buffer.append(fac.getLocation());
            buffer.append(airport.getName());
            buffer.append(Converters.XMLHelper.protectSpecialCharacters(fac.getName()));
            buffer.append(fac.getCommodity() != null ? fac.getCommodity().trim() : "");

            int totalgates = fbo.getFboSize() * airport.getFboSlots();
            int rentedgates;
            if (fac.getReservedSpace() >= 0)
            {
                //Owner facility record, see if there are any non-rented slots
                FboFacilityBean[] facrenters = data.getFboRenterFacilities(fbo);
                int rentcount = 0;

                for (FboFacilityBean facrenter : facrenters)
                {
                    rentcount += facrenter.getSize();
                }

                rentedgates = fac.getReservedSpace() + ((totalgates - fac.getReservedSpace()) - rentcount);
            }
            else
            {
                rentedgates = fac.getSize();
            }

            buffer.append(totalgates);
            buffer.append(rentedgates);

            buffer.append(fac.getPublicByDefault() ? "Yes" : "No");
            buffer.append(fac.getIcaoSet() != null ? fac.getIcaoSet() : "");
            buffer.append(Converters.XMLHelper.protectSpecialCharacters(fbo.getName()));
            buffer.append((fbo.isActive() ? "Open" : "Closed"));

            buffer.newrow();
        }
	}
	
	private void AddXMLFacilityItems(Converters.xmlBuffer buffer, FboFacilityBean[] facs)
	{
        for (FboFacilityBean fac : facs)
        {
            FboBean fbo = data.getFbo(fac.getFboId());
            AirportBean airport = data.getAirport(fac.getLocation());

            buffer.append("<Facility>\n");
            buffer.append("Icao", fac.getLocation());
            buffer.append("Location", airport.getName());
            buffer.append("Carrier", Converters.XMLHelper.protectSpecialCharacters(fac.getName()));
            buffer.append("CommodityNames", fac.getCommodity() != null ? fac.getCommodity().trim() : "");

            int totalgates = fbo.getFboSize() * airport.getFboSlots();
            int rentedgates;
            if (fac.getReservedSpace() >= 0)
            {
                //Owner facility record, see if there are any non-rented slots
                FboFacilityBean[] facrenters = data.getFboRenterFacilities(fbo);
                int rentcount = 0;
                for (FboFacilityBean facrenter : facrenters)
                {
                    rentcount += facrenter.getSize();
                }

                rentedgates = fac.getReservedSpace() + ((totalgates - fac.getReservedSpace()) - rentcount);
            }
            else
            {
                rentedgates = fac.getSize();
            }

            buffer.append("GatesTotal", totalgates);
            buffer.append("GatesRented", rentedgates);

            buffer.append("JobsPublic", fac.getPublicByDefault() ? "Yes" : "No");
            buffer.append("Destinations", fac.getIcaoSet() != null ? fac.getIcaoSet() : "");
            buffer.append("Fbo", Converters.XMLHelper.protectSpecialCharacters(fbo.getName()));
            buffer.append("Status", (fbo.isActive() ? "Open" : "Closed"));
            buffer.append("</Facility>\n");
        }
	}

	private void AddCSVFboItems(Converters.csvBuffer buffer, FboBean[] fbos, boolean showsupplies)
	{
		if(buffer.isHeaderEmpty())
		{			
			buffer.appendHeaderItem("Status");
			buffer.appendHeaderItem("Airport");
			buffer.appendHeaderItem("Name");
			buffer.appendHeaderItem("Owner");
			buffer.appendHeaderItem("Icao");
			buffer.appendHeaderItem("Location");
			buffer.appendHeaderItem("Lots");
			buffer.appendHeaderItem("RepairShop");
			buffer.appendHeaderItem("Gates");
			buffer.appendHeaderItem("GatesRented");
			buffer.appendHeaderItem("Fuel100LL");
			buffer.appendHeaderItem("FuelJetA");
			buffer.appendHeaderItem("BuildingMaterials");
			buffer.appendHeaderItem("Supplies");
			buffer.appendHeaderItem("SuppliesPerDay");
			buffer.appendHeaderItem("SuppliedDays");
			buffer.appendHeaderItem("SellPrice");
		}

        for (FboBean fbo : fbos)
        {
            AirportBean airport = data.getAirport(fbo.getLocation());

            UserBean fboowner = data.getAccountById(fbo.getOwner());
            int groupOwnerid = data.accountUltimateGroupOwner(fbo.getOwner());
            UserBean ultimateOwner = data.getAccountById(groupOwnerid);

            int totalSpace = fbo.getFboSize() * airport.getFboSlots();
            int rented = data.getFboFacilityBlocksInUse(fbo.getId());
            GoodsBean fuel = data.getGoods(fbo.getLocation(), fbo.getOwner(), GoodsBean.GOODS_FUEL100LL);
            GoodsBean jeta = data.getGoods(fbo.getLocation(), fbo.getOwner(), GoodsBean.GOODS_FUELJETA);

            buffer.append((fbo.isActive() ? "Active" : "Closed"));
            buffer.append(airport.getName());
            buffer.append(fbo.getName());
            buffer.append(Converters.XMLHelper.protectSpecialCharacters(fboowner.getName()) + (fboowner.isGroup() ? "(" + Converters.XMLHelper.protectSpecialCharacters(ultimateOwner.getName()) + ")" : ""));
            buffer.append(fbo.getLocation());
            buffer.append(airport.getCity() + ", " + airport.getCountry());
            buffer.append(fbo.getFboSize());
            buffer.append(((fbo.getServices() & FboBean.FBO_REPAIRSHOP) > 0 ? "Yes" : "No"));
            buffer.append(((fbo.getServices() & FboBean.FBO_PASSENGERTERMINAL) > 0 ? "" + totalSpace : "No Passenger Terminal"));
            buffer.append(((fbo.getServices() & FboBean.FBO_PASSENGERTERMINAL) > 0 ? "" + rented : "No Passenger Terminal"));
            buffer.append(fuel != null ? fuel.getAmount() : 0);
            buffer.append(jeta != null ? jeta.getAmount() : 0);
            buffer.append(data.getGoodsQty(fbo, GoodsBean.GOODS_BUILDING_MATERIALS));

            if (showsupplies)
            {
                buffer.append(data.getGoodsQty(fbo, GoodsBean.GOODS_SUPPLIES));
            }
            else
            {
                buffer.append("0");
            }

            buffer.append(fbo.getSuppliesPerDay(airport));

            if (showsupplies)
            {
                buffer.append(data.getGoodsQty(fbo, GoodsBean.GOODS_SUPPLIES) / fbo.getSuppliesPerDay(airport));
            }
            else
            {
                buffer.append("0");
            }

            buffer.appendMoney(fbo.getPrice());
            buffer.newrow();
        }
	}
	
	private void AddXMLFboItems(Converters.xmlBuffer buffer, FboBean[] fbos, boolean showsupplies)
	{
        for (FboBean fbo : fbos)
        {
            AirportBean airport = data.getAirport(fbo.getLocation());

            UserBean fboowner = data.getAccountById(fbo.getOwner());
            int groupOwnerid = data.accountUltimateGroupOwner(fbo.getOwner());
            UserBean ultimateOwner = data.getAccountById(groupOwnerid);

            int totalSpace = fbo.getFboSize() * airport.getFboSlots();
            int rented = data.getFboFacilityBlocksInUse(fbo.getId());
            GoodsBean fuel = data.getGoods(fbo.getLocation(), fbo.getOwner(), GoodsBean.GOODS_FUEL100LL);
            GoodsBean jeta = data.getGoods(fbo.getLocation(), fbo.getOwner(), GoodsBean.GOODS_FUELJETA);

            buffer.append("<FBO>\n");
            buffer.append("Status", (fbo.isActive() ? "Active" : "Closed"));
            buffer.append("Airport", airport.getName());
            buffer.append("Name", Converters.XMLHelper.protectSpecialCharacters(fbo.getName()));
            buffer.append("Owner", Converters.XMLHelper.protectSpecialCharacters(fboowner.getName()) + (fboowner.isGroup() ? "(" + Converters.XMLHelper.protectSpecialCharacters(ultimateOwner.getName()) + ")" : ""));
            buffer.append("Icao", fbo.getLocation());
            buffer.append("Location", airport.getCity() + ", " + airport.getCountry());
            buffer.append("Lots", fbo.getFboSize());
            buffer.append("RepairShop", ((fbo.getServices() & FboBean.FBO_REPAIRSHOP) > 0 ? "Yes" : "No"));
            buffer.append("Gates", ((fbo.getServices() & FboBean.FBO_PASSENGERTERMINAL) > 0 ? "" + totalSpace : "No Passenger Terminal"));
            buffer.append("GatesRented", ((fbo.getServices() & FboBean.FBO_PASSENGERTERMINAL) > 0 ? "" + rented : "No Passenger Terminal"));
            buffer.append("Fuel100LL", fuel != null ? fuel.getAmount() : 0);
            buffer.append("FuelJetA", jeta != null ? jeta.getAmount() : 0);
            buffer.append("BuildingMaterials", data.getGoodsQty(fbo, GoodsBean.GOODS_BUILDING_MATERIALS));

            if (showsupplies)
            {
                buffer.append("Supplies", data.getGoodsQty(fbo, GoodsBean.GOODS_SUPPLIES));
            }
            else
            {
                buffer.append("Supplies", 0);
            }

            buffer.append("SuppliesPerDay", fbo.getSuppliesPerDay(airport));

            if (showsupplies)
            {
                buffer.append("SuppliedDays", data.getGoodsQty(fbo, GoodsBean.GOODS_SUPPLIES) / fbo.getSuppliesPerDay(airport));
            }
            else
            {
                buffer.append("SuppliedDays", 0);
            }

            buffer.appendMoney("SellPrice", fbo.getPrice());
            buffer.append("</FBO>\n");
        }
	}

	//NOTE NOTE NOTE!!!
	//Any changes here need to be reflected in Data.java's version of this method!
	public void AddCSVJobItems(Converters.csvBuffer buffer, AssignmentBean[] assignments)
	{
		if(buffer.isHeaderEmpty())
		{
			buffer.appendHeaderItem("Id");			
			buffer.appendHeaderItem("Location");
			buffer.appendHeaderItem("ToIcao");
			buffer.appendHeaderItem("FromIcao");
			buffer.appendHeaderItem("Amount");
			buffer.appendHeaderItem("UnitType");
			buffer.appendHeaderItem("Commodity");			
			buffer.appendHeaderItem("Pay");
			buffer.appendHeaderItem("Expires");
			buffer.appendHeaderItem("ExpireDateTime");
			buffer.appendHeaderItem("PtAssignment");
			buffer.appendHeaderItem("All-In");
		}

        for (AssignmentBean assignment : assignments)
        {
            buffer.append(assignment.getId());
            buffer.append(assignment.getLocation());
            buffer.append(assignment.getTo());
            buffer.append(assignment.getFrom());
            buffer.append(assignment.getAmount());
            buffer.append(assignment.getSUnits());
            buffer.append(assignment.getCommodity() == null ? "Group Assignment" : Converters.XMLHelper.protectSpecialCharacters(assignment.getCommodity()));
            buffer.appendMoney(assignment.calcPay());
            buffer.append(assignment.getSExpires());
            buffer.append(assignment.getExpiresGMTDate());
            buffer.append(Boolean.toString(assignment.isPtAssignment()));
            buffer.append(assignment.getType() == AssignmentBean.TYPE_ALLIN);
            buffer.newrow();
        }
	}

	private void AddXMLJobItems(Converters.xmlBuffer buffer, AssignmentBean[] assignments)
	{
        for (AssignmentBean assignment : assignments)
        {
            buffer.append("<Assignment>\n");
            buffer.append("Id", assignment.getId());
            buffer.append("Location", assignment.getLocation());
            buffer.append("ToIcao", assignment.getTo());
            buffer.append("FromIcao", assignment.getFrom());
            buffer.append("Amount", assignment.getAmount());
            buffer.append("UnitType", assignment.getSUnits());
            buffer.append("Commodity", Converters.XMLHelper.protectSpecialCharacters(assignment.getCommodity()));
            buffer.appendMoney("Pay", assignment.calcPay());
            buffer.append("Expires", assignment.getSExpires());
            buffer.append("ExpireDateTime", assignment.getExpiresGMTDate());
            buffer.append("PtAssignment", Boolean.toString(assignment.isPtAssignment()));
            buffer.append("</Assignment>\n");
        }
	}
}
