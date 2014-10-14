<%@ page language="java"
	import="net.fseconomy.data.*, net.fseconomy.util.*, java.util.*"
%>
<%
    Data data = (Data)application.getAttribute("data");
%>
<jsp:useBean id="user" class="net.fseconomy.data.UserBean" scope="session"></jsp:useBean>
<%
	if(user == null || !user.isLoggedIn()) 
	{
		out.print("<script type=\"text/javascript\">document.location.href=\"index.jsp\"</script>");
		return;
	}

	//setup return page if action used
	String returnPage = request.getRequestURI();
	response.addHeader("referer", request.getRequestURI());

	Map<String, Integer> info = null;
	double pilothours = 0; 
	boolean grounded = pilothours > 30;
	int condition = 0;
	int assignmentsTotalPay=0;
	int assignmentsHoldPay=0;
	int assignmentsTotalAll=0;
	
	String event = request.getParameter("event");
	
	if (event != null && "holdrental".contentEquals(event))
	{
		String reg = request.getParameter("reg");
		int id = Integer.parseInt(request.getParameter("id"));
		boolean hold = Boolean.parseBoolean(request.getParameter("hold"));
		data.setHoldRental(reg, id, !hold);
	}
	
	AssignmentBean[] assignments = data.getAssignmentsForUser(user.getId());
	AircraftBean[] aircraft = data.getAircraftForUser(user.getId());
	boolean haveAircraft = aircraft.length > 0, haveFlight = false;	
	
	boolean isAllInPresent = false;
	for (int c=0; c< assignments.length; c++)
	{
	    if (assignments[c].getType() == 1) 
	    {
	   		isAllInPresent = true;
	   	}
	}
	
	try
	{
		pilothours = data.getNumberOfHours(user.getName(), 48);
	}
	catch(DataError e)
	{
		//Eat it
	}
%>
<!DOCTYPE html>
<html lang="en">
<head>

	<title>FSEconomy terminal</title>

	<meta http-equiv="X-UA-Compatible" content="IE=edge" />
    <meta http-equiv="Content-Type" content="text/html;charset=utf-8"/>

	<link rel="stylesheet" type="text/css" href="theme/Master.css" />
	<link rel="stylesheet" type="text/css" href="theme/tablesorter-style.css" />

	<% //regressed jquery so that lightbox would work %>
	<script src="scripts/jquery.min.js"></script>
	<script src="scripts/jquery-ui.min.js"></script>
	<script src="https://maps.google.com/maps/api/js?sensor=false"></script>

	<script type='text/javascript' src='scripts/jquery.tablesorter.js'></script>
	<script type='text/javascript' src="scripts/jquery.tablesorter.widgets.js"></script>
	<script type='text/javascript' src='scripts/parser-checkbox.js'></script>
	<script type='text/javascript' src='scripts/parser-timeExpire.js'></script>

	<script src="scripts/PopupWindow.js"></script>
	<script src="fancybox/jquery.fancybox-1.3.1.pack.js"></script>
	<link href="fancybox/jquery.fancybox-1.3.1.css" rel="stylesheet" type="text/css" />
	<script src="scripts/location-mapper.js"></script>

	<script>
		var gmap = new PopupWindow();
		
		function doSubmit(id)
		{
		     document.lflightForm.id.value = id;
		     document.lflightForm.submit();
		}
		function doSubmit1(id)
		{
		     document.hflightForm.id.value = id;
		     document.hflightForm.submit();
		}
		function doSubmit2(reg)
		{
		     document.aircraftForm.reg.value = reg;
		     document.aircraftForm.type.value = "remove";
		     document.aircraftForm.submit();
		}
		function doSubmit3(id)
		{
		     document.aircraftForm.id.value = id;
		     document.aircraftForm.type.value = "refuel";
		     document.aircraftForm.submit();
		}
		function doSubmit4(id)
		{
		     document.groupForm.id.value = id;
		     document.groupForm.submit();
		}
		function doSubmit5(id)
		{
		     document.lflightForm.id.value = id;
		     document.lflightForm.type.value = "hold";
		     document.lflightForm.submit();
		}
		function doSubmit8(id)
		{
		     document.hflightForm.id.value = id;
		     document.hflightForm.type.value = "load";
		     document.hflightForm.submit();
		}
		function doSubmitX(reg,id,hold)
		{
			var form = document.getElementById("aircraftForm");
			
			form.action = "myflight.jsp";
			form.event.value = "holdrental";
			form.reg.value = reg;
			form.id.value = id;
			form.hold.value = hold;

			form.submit();
		}
		function temp(form)
		{
		 	var f = parseFloat(form.DegF.value, 10);
		 	var c = 0;
		 	c = (f - 32.0) * 5.0 / 9.0;
		 	form.DegC.value = c;
		}
		function checkAll(field)
		{
			for (i = 0; i < field.length; i++)
		     	field[i].checked = true ;
		
			field.checked = true ;  // needed in case of only one box
		}
		function checkNone(field)
		{
			for (i = 0; i < field.length; i++)
		     	field[i].checked = false ;
		
		   	field.checked = false ;  // needed in case of only one box
		}
	</script>
	
	<script type="text/javascript">
	
		$(function() 
		{		
			$.extend($.tablesorter.defaults, 
			{
				widthFixed: false,
				widgets : ['zebra','columns']
			});
		
			$('.holdTable').tablesorter();		
		});
		
		$(function() 
		{		
			$.extend($.tablesorter.defaults, 
			{
				widthFixed: false,
				widgets : ['zebra','columns']
			});
		
			$('.assignmentTable').tablesorter();		
		});
		
	</script>
</head>

<body>
<jsp:include flush="true" page="top.jsp" />
<jsp:include flush="true" page="menu.jsp" />
<div id="wrapper">
<div class="content">
<%
	if (haveAircraft)
	{
		String from = aircraft[0].getLocation();
		for (int c=0; c< assignments.length; c++)
		{
		      if (assignments[c].getFrom().equals(from))
		            haveFlight = true;
		}
		try
		{
			info = data.getMyFlightInfo(aircraft[0], user.getId());
		}
		catch(DataError e)
		{
			//eat it			
		}
	}
	else if (!grounded)
	{
%>
		<h2>Status: No aircraft</h2>
		You need to rent an aircraft before you can start your flight.<br />
<%
	}

	UserBean theGroup[] = null;
	Integer grp;
	
	grp = info != null ? (Integer) info.get("group") : null;
	int grpId = grp == null ? -1 : grp.intValue();	
	boolean groupFlight = grpId == -1 ? false : true;
	
	if( groupFlight )
	    theGroup = data.getGroupById(grpId);

	if (haveAircraft && aircraft[0].getLocation() == null)
	{
%>
		<h2>Status: Flight in progress<%= groupFlight ? " for\"" + theGroup[0].getName() + "\"" : "" %></h2>
		There is a flight in progress. If you cannot complete this flight you can cancel it.
		<form method="post" action="userctl">
		<input type="hidden" name="event" value="Cancel"/>
		<input type="hidden" name="returnpage" value="<%=returnPage%>"/>
		<input type="submit" class="button" value="Cancel" />
		</form>
<%
	}
	else if (haveAircraft && !grounded)
	{
	    UserBean groups[] = data.getGroupsForUser(user.getId());
%>
		<h2>Status: Ready for departure</h2>

		You can depart from 
			<a href="<%= response.encodeURL("airport.jsp?icao=" + aircraft[0].getLocation()) %>">
				<%= aircraft[0].getLocation() %>
			</a> 
			now.<br/>
<%
		if (groups.length > 0)
		{
	      	Integer group = (Integer) info.get("group");
	      	UserBean thisGroup = null;
	      	int groupId = group == null ? -1 : group.intValue();
	      	if (groupId != -1)
	      	{
	            for (int c=0; c < groups.length; c++)
	            {
	            	if (groups[c].getId() == groupId)
	                	thisGroup = groups[c];
	            }
	      	}
			if (thisGroup == null)
	    	{
%>
	                 This will not be a group flight.
<%				if (info.containsKey("hasAssignment") == false)
     			{
%>
             		You cannot fly for a group unless you have an assignment aboard.
<%
				}
     			else
     			{	
%>		        	<form method="post" action="userctl" name="groupForm">
			        <input type="hidden" name="event" value="flyForGroup"/>
		    	    <input type="hidden" name="id"/>
					<input type="hidden" name="returnpage" value="<%=returnPage%>"/>
<%		            if (!isAllInPresent) 
    	    		{ 
%>      	         	If you want to fly this flight for a group, select its name:
            	    	<ul>
<%              		for (int c=0; c < groups.length; c++)
                		{
%>                 			<li><a class="link" href="javascript:doSubmit4(<%= groups[c].getId()%>)">Fly for <%=groups[c].getName() %></a></li>
<%              		}
%>		            	</ul>
<%					} 
        	    	else 
            		{ 
%> 						All-In job in queue, cannot be flown for a group. 
<%					} 
%>          		</form>
<%   			}
			}
    		else
    		{
%>				This flight will be flown for the group "<%=thisGroup.getName() %>".
<%  		}
		}
	} 
	else if (grounded) 
	{ 
%>  	<h2>Status: Flight crew grounded</h2>
        Pilot hours flown in last 48: <b><a class="normal" href="<%= response.encodeURL("hours.jsp") %>"> <%=Formatters.oneDecimal.format(pilothours) %></a></b>
<%  }
	if (haveAircraft)
	{
		double aircrafthours = aircraft[0].getHoursSinceLastCheck();
		double enginehours = aircraft[0].getEngineHours();
		condition = aircraft[0].getCondition();
		ModelBean[] Models =
		data.getModelById(aircraft[0].getModelId());
	    int tbo = Models[0].getFueltype()==0 ?
		AircraftBean.TBO_RECIP/3600 : AircraftBean.TBO_JET/3600;
	
	    if (aircraft[0].getOwner() == 0)
	    	aircrafthours = 0;
	
		if (aircraft[0].isBroken())
	    {
%>	   		<div class="dataTable" style="color: red">
				<b>WARNING:</b> This aircraft is prohibited from commercial operations until repairs have been made.<br>
           		Please fly to the nearest Repair Station and have your aircraft serviced.<br>
           		<%=aircraft[0].getAllowFix()==0 ? "  Renters are not authorized to make repairs." :  "  Renters are authorized to make repairs." %>
         	</div>
<%		}
    	if (aircrafthours > 90  || enginehours > tbo-20)
    	{
%>
	       	<div class="dataTable" style="color: red" >
	       	<b>WARNING:</b> This aircraft is prohibited from commercial
			operations if 100-hour inspection time is exceeded or if total engine
			time exceeds TBO.<br><br>
	        Hours since last 100-hour inspection: <%=aircrafthours > 90 ? "<b>":""%><%= Formatters.oneDecimal.format(aircrafthours) %><%= aircrafthours > 90 ? "</b>":""%><br>
	        Total Engine Time: <%=enginehours > tbo-20 ? "<b>":""%> <%=Formatters.oneDecimal.format(enginehours) %><%= enginehours > tbo-20 ? "</b>":""%><br>
	        TBO: <%=tbo%>
	        </div>
<%		}

    	boolean isNegCost;
    	int rentalCost = aircraft[0].wasWetRent() ? aircraft[0].getRentalPriceWet() : aircraft[0].getRentalPriceDry();

	    // 4/21/11 - Airboss
	    //changed to only show if both cash and balance values would not cover aircraft cost as per Jimmy's request
	    if( groupFlight)
	    {
	      	isNegCost =((theGroup[0].getMoney()+theGroup[0].getBank()) < (rentalCost*.5));
	    }
	    else
	    {
	      	double usableBank = user.getBank() + user.getMoney();
	        if( usableBank < 0)
	        	usableBank = 40000 + usableBank;
	        else
	        	usableBank += 40000;
	 
	        isNegCost = (usableBank < (rentalCost*.5));
	    }
	
		if(isNegCost)
		{
%>      	<div class="dataTable" style="color: red" >
       		<b>WARNING:</b> <%= groupFlight ? "\"" + theGroup[0].getName() + "\"" : "Your" %> cash balance provides less then 30 minutes of flight time at a rental cost of <%= Formatters.currency.format(rentalCost) %> per hour!!
        	</div>
<%  	}
	}
%>
<%-- chuck229  --%>
<% 
	if(assignments.length == 0) 
	{ 
%>		<div class="message">Holding Area - Empty</div>
<% 
	} 
	else 
	{ 
%>		<form method="post" action="userctl" name="hflightForm">
	    <input type="hidden" name="event" value="Assignment"/>
	    <input type="hidden" name="id" />
	    <input type="hidden" name="type" value="remove" />
		<input type="hidden" name="returnpage" value="<%=returnPage%>"/>
	    
	    <table class="holdTable tablesorter-default tablesorter">
	    <caption>Holding Area</caption>
	    <thead>
	    <tr>
	    	<th class="sorter-checkbox" style="width: 35px">Select</th>
			<th class="numeric" style="width: 75px">Pay</th>
			<th style="width: 60px">Location</th>
			<th style="width: 60px">From</th>
			<th style="width: 60px">Dest</th>
			<th class="numeric" style="width: 35px">NM</th>
			<th class="numeric" style="width: 45px">Bearing</th>
			<th style="max-width: 400px">Cargo</th>
			<th style="width: 35px">Type</th>
			<th style="width: 75px">Aircraft</th>
           	<th class="sorter-timeExpire" style="width: 75px">Expires</th>
           	<th style="width: 75px">Status</th>
           	<th>Job Comments</th>
	    </tr>
	    </thead>
	    <tbody>
<%
     	for (int c=0; c< assignments.length; c++)
     	{
     		if (assignments[c].getActive() == 2)
     		{
           		AssignmentBean assignment = assignments[c];
           		String image = "img/set2_" + assignment.getActualBearingImage(data) + ".gif";
           		String aircraftReg = assignments[c].getAircraft();
           		String status;
           		
           		if (assignment.getActive() > 0)
           		{
                 	if (assignment.getActive() == 2)
                       	status = "On Hold";
                 	else
                       	status = "Enroute";
           		}
           		else if (info != null && info.containsKey((new Integer(assignment.getId())).toString()))
           		{
                 	status = "Departing";
           		}
           		else
           		{
                 	status = "Ready";
           		}
           		
           		assignmentsHoldPay += assignment.calcPay();
           		
           		AirportBean destination = assignment.getDestinationAirport(data);
           		AirportBean from = assignment.getFromAirport(data);
           		AirportBean location = assignment.getLocationAirport(data);
%>
		     	<tr <%= Data.oddLine(c) %>>
		     	<td>
					<div class="checkbox" >
						<input class="css-checkbox" type="checkbox" id="mycheckbox<%=c%>" name="select" value="<%=assignment.getId() %>"/>
						<label class="css-label" for="mycheckbox<%=c%>"></label>
					</div>
				</td>
		        <td class="numeric"><%=Formatters.currency.format(assignment.calcPay()) %></td>
		        <td>
		        	<a href="#" onclick="gmap.setSize(620,520);gmap.setUrl('gmap.jsp?icao=<%=location.getIcao() %>&icaod=<%= destination.getIcao()%>');gmap.showPopup('gmap');return false;" id="gmap">
		        	<img src="<%=location.getDescriptiveImage(data.getFboByLocation(assignment.getLocation()))%>" style="border-style: none; vertical-align:middle;" />
		        	</a>
		        	<a class="normal" title="<%=location.getTitle() %>" href="<%= response.encodeURL("airport.jsp?icao="+ assignment.getLocation()) %>">
		        	<%= assignment.getLocation() %>
		        	</a>
		        </td>
		        <td>
		        	<a href="#" onclick="gmap.setSize(620,520);gmap.setUrl('gmap.jsp?icao=<%=from.getIcao() %>&icaod=<%=(from.getIcao().equals(location.getIcao()))?destination.getIcao():location.getIcao()%>');gmap.showPopup('gmap');return false;">
		        		<img src="<%=from.getDescriptiveImage(data.getFboByLocation(assignment.getFrom()))%>" style="border-style: none; vertical-align:middle;" />
		        	</a>
		        	<a class="normal" title="<%=from.getTitle() %>" href="<%= response.encodeURL("airport.jsp?icao=" + assignment.getFrom()) %>">
		        		<%= assignment.getFrom() %>
		        	</a>
		        </td>
		        <td>
		        	<a href="#" onclick="gmap.setSize(620,520);gmap.setUrl('gmap.jsp?icao=<%=location.getIcao() %>&icaod=<%= destination.getIcao()%>');gmap.showPopup('gmap');return false;">
		        		<img src="<%=destination.getDescriptiveImage(data.getFboByLocation(assignment.getTo()))%>" style="border-style: none; vertical-align:middle;" />
		        	</a>
		        	<a class="normal" title="<%=destination.getTitle() %>" href="<%=response.encodeURL("airport.jsp?icao=" + assignment.getTo()) %>">
		        		<%=assignment.getTo() %>
		        	</a>
		        </td>
		        <td class="numeric"><%= assignment.getActualDistance(data)%></td>
		        <td class="numeric">
		        	<%= assignment.getActualBearing(data) %> 
		        	<img src="<%= image %>">
		        </td>
		        <td><%= assignment.getSCargo() %></td>
		        <td><%= assignment.getType() == AssignmentBean.TYPE_ALLIN ? "A" : "T" %></td>
		        <td><%= aircraftReg == null ? "[not provided]" : aircraftReg%></td>
		        <td><%=assignments[c].getSExpires() %></td>
		        <td><%= status %></td>
		        <td><%= assignment.getComment() %></td>
		     </tr>
<%			}
		}
%>
    </tbody>
    </table>
    <br>
    <div>
    <a class="link" href="javascript:checkAll(document.hflightForm.select)">Select All</a>	|
    <a class="link" href="javascript:checkNone(document.hflightForm.select)">De-Select</a>
    <input type="button" name="load_Selectedx" value="Load Selected Assignments"  id="load_Selectedx">
    <input type="button" name="cancel_Selectedx" value="Cancel Selected Assignments" onclick="doSubmit1(document.hflightForm.select)">
    <br><br>
    <input type="hidden" name="countLoadedAllIn" id="countLoadedAllIn" value="0" disabled>
    </div>
    </form>
    
<%
		if(assignmentsHoldPay != 0)
		{
%>    <p>Total Value in Holding Area: <b><%=Formatters.currency.format(assignmentsHoldPay) %></b>
    
    
<%	
		}
	} 
%>
<script type="text/javascript">
   $("#load_Selectedx").click(function(e){
       e.preventDefault();
       //check if there are All-in jobs in the loaded table
       $.each($(".assignmentTable").find("td"), function(){
           if($(this).hasClass('assignmentType')){
               if($(this).html() == 'A'){
       $("#countLoadedAllIn").val("1");
               }
           }
       });
    if($("#countLoadedAllIn").val() == "0"){
  		doSubmit8(document.hflightForm.select);
  	}else{
  		alert('You cannot add jobs when an All-In job is loaded.');
  	}
   });
</script>


<%-- chuck229 --%>
	<script type="text/javascript">
		var loc = new Object();
		var assignment = new Object();
		var i = 0;
	</script>

<% 
	if (assignments.length == 0) 
	{ 
%>
     	<div class="message">No assignments selected</div>
<% 	} 
	else 
	{ 
%>		<form method="post" action="userctl" name="lflightForm">
    	<input type="hidden" name="event" value="Assignment"/>
    	<input type="hidden" name="id" />
    	<input type="hidden" name="type" value="remove" />
		<input type="hidden" name="returnpage" value="<%=returnPage%>"/>
    	
    	<table class="assignmentTable tablesorter-default tablesorter">
    	<caption>Loading Area</caption>
    	<thead>
    	<tr>
	    	<th class="sorter-checkbox" style="width: 35px;">Select</th>
			<th class="numeric" style="width: 75px;">Pay</th>
			<th style="width: 60px;">Location</th>
			<th style="width: 60px;">From</th>
			<th style="width: 60px;">Dest</th>
			<th class="numeric" style="width: 35px;">NM</th>
			<th class="numeric" style="width: 45px;">Bearing</th>
			<th style="max-width: 400px;">Cargo</th>
			<th style="width: 35px;">Type</th>
			<th style="width: 75px;">Aircraft</th>
           	<th class="sorter-timeExpire" style="width: 75px;">Expires</th>
           	<th style="width: 75px;">Status</th>
           	<th style="max-width: 400px;">Job Comments</th>
	   	</tr>
     	</thead>
     	<tbody>
<%
		//All-In processing - see if any of the assignments in queue are of type=1
		for (int c=0; c< assignments.length; c++)
    	{
	      	if (assignments[c].getType() == 1) 
	     		isAllInPresent = true;
     	}

		//normal processing resumes here
     	for (int c=0; c< assignments.length; c++)
     	{
     		if (assignments[c].getActive() != 2)
     		{
           		AssignmentBean assignment = assignments[c];
           		String image = "img/set2_" +assignment.getActualBearingImage(data) + ".gif";
           		String aircraftReg = assignments[c].getAircraft();
           		String status;
           		
           		//All-In logic
           		if (!isAllInPresent) 
           		{ //do normal processing as before
           			if (assignment.getActive() > 0 )
           			{
                   		if (assignment.getActive() == 2)
                         	status = "On Hold";
                   		else
                         	status = "Enroute";
           			}
             		else if (info != null && info.containsKey((new Integer(assignment.getId()).toString())))
             		{
                   		status = "Departing";
             		}
             		else
             		{
                   		status = "Selected";
             		}
           		}
           		else 
           		{
           			//System.out.println("now in the All-In processing area...");
           			if (isAllInPresent && assignment.getType() == 1 && assignment.getActive() == 0) //this isthe actual All-In job so set it to Ready to Depart
           			{
               			status = "Onboard";
           			}
               		else
               		{
               			if (assignment.getActive() > 0)
               			{
                       		if (assignment.getActive() == 2)
                            	status = "On Hold";
                       		else
                             	status = "Enroute";
               			}
               			else
               			{
               				status = "Selected"; //everything has to be ignoredcause there is an All-In job
               			}
               		}
           		}
           		assignmentsTotalPay += assignment.calcPay();

           		AirportBean destination = assignment.getDestinationAirport(data);
           		AirportBean from = assignment.getFromAirport(data);
           		AirportBean location = assignment.getLocationAirport(data);

				String icao = location.getIcao();
				String destIcao = destination.getIcao();
				AirportBean mapAirport = data.getAirport(icao);
				double latl = mapAirport.getLat();
				double lonl = mapAirport.getLon();
				AirportBean mapDestAirport = data.getAirport(destIcao);
				double destLatl = mapDestAirport.getLat();
				double destLonl = mapDestAirport.getLon();
%>
	<script type="text/javascript">
		if (typeof loc['<%=icao%>'] != 'undefined') {
			var len = loc['<%=icao%>'].length;
		} 
		else {
			loc['<%=icao%>'] = [];
			len = 0;
		}
		loc['<%=icao%>'][len] = [];
		loc['<%=icao%>'][len].latl = <%=latl%>;
		loc['<%=icao%>'][len].lonl = <%=lonl%>;
		loc['<%=icao%>'][len].pay = "<%=Formatters.currency.format(assignment.calcPay())%>";
		loc['<%=icao%>'][len].cargo = "<%=assignment.getSCargo()%>";
		loc['<%=icao%>'][len].status =
		"<%=status%>".replace(/\s/g,'').toLowerCase();
		loc['<%=icao%>'][len].dist = <%=assignment.getActualDistance(data)%>;
		loc['<%=icao%>'][len].dest = [];
		loc['<%=icao%>'][len].dest.icao = '<%=destIcao%>';
		loc['<%=icao%>'][len].dest.latl = <%=destLatl%>;
		loc['<%=icao%>'][len].dest.lonl = <%=destLonl%>;
		var mapCenter = {latl: <%=latl%>, lonl: <%=lonl%>};
	</script>

	     		<tr>
	     			<td>
					<div class="checkbox" >
						<input class="css-checkbox" type="checkbox" id="mycheckbox<%=c%>" name="select" value="<%=assignment.getId() %>" <%=assignment.getActive() != 0 ? "disabled" : "" %> />
						<label class="css-label" for="mycheckbox<%=c%>"></label>
					</div>
					</td>
		        	<td class="numeric"><%=Formatters.currency.format(assignment.calcPay()) %></td>
		       	 	<td>
		        		<a href="#" onclick="gmap.setSize(620,520);gmap.setUrl('gmap.jsp?icao=<%=location.getIcao() %>&icaod=<%= destination.getIcao()%>');gmap.showPopup('gmap');return false;">
		        			<img src="<%=location.getDescriptiveImage(data.getFboByLocation(assignment.getLocation()))%>" style="border-style: none; vertical-align:middle;" />
		        		</a>
		        		<a title="<%=location.getTitle() %>" href="<%= response.encodeURL("airport.jsp?icao="+ assignment.getLocation()) %>">
		        			<%= assignment.getLocation() %>
		        		</a>
		       		</td>
		        	<td>
		        		<a href="#" onclick="gmap.setSize(620,520);gmap.setUrl('gmap.jsp?icao=<%= from.getIcao() %>&icaod=<%=(from.getIcao().equals(location.getIcao()))?destination.getIcao():location.getIcao()%>');gmap.showPopup('gmap');return false;">
		        			<img src="<%=from.getDescriptiveImage(data.getFboByLocation(assignment.getFrom()))%>" style="border-style: none; vertical-align:middle;" />
		        		</a>
		        		<a title="<%=from.getTitle() %>" href="<%= response.encodeURL("airport.jsp?icao=" +assignment.getFrom()) %>">
		        			<%= assignment.getFrom() %>
		        		</a>
		        	</td>
		        	<td>
		        		<a href="#" onclick="gmap.setSize(620,520);gmap.setUrl('gmap.jsp?icao=<%= location.getIcao() %>&icaod=<%= destination.getIcao()%>');gmap.showPopup('gmap');return false;">
		        			<img src="<%=destination.getDescriptiveImage(data.getFboByLocation(assignment.getTo()))%>" style="border-style: none; vertical-align:middle;" />
		        		</a>
		        		<a title="<%=destination.getTitle() %>" href="<%=response.encodeURL("airport.jsp?icao=" + assignment.getTo()) %>">
		        			<%=assignment.getTo() %>
		        		</a>
		        	</td>
		        	<td class="numeric"><%= assignment.getActualDistance(data)%></td>
		        	<td class="numeric">
		        		<%= assignment.getActualBearing(data) %> 
		        		<img src="<%= image %>">
		        	</td>
		        	<td><%= assignment.getSCargo() %></td>
		        	<td class="assignmentType"><%= assignment.getType() == AssignmentBean.TYPE_ALLIN ? "A" : "T" %></td>
		        	<td><%= aircraftReg == null ? "[not provided]" : aircraftReg%></td>
		        	<td><%= assignments[c].getSExpires() %></td>
		        	<td><%= status %></td>
		        	<td><%= assignment.getComment() %></td>
				</tr>
<%
     			}
     		}
%>
     </tbody>
     </table>
     <br>
     <a class="link" href="javascript:checkAll(document.lflightForm.select)">Select All</a>|
     <a class="link" href="javascript:checkNone(document.lflightForm.select)">De-Select</a>
     <input type="button" name="hold_Selected" value="Hold Selected Assignments" onClick="doSubmit5(document.lflightForm.select)" />
     <input type="button" name="add_Selected" value="Cancel Selected Assignments" onClick="doSubmit(this.form.select)" />
     <a href="#lb" id="show-map">Map My Assignments</a>
     <br><br>
     </form>
<%
     		if (info != null) 
     		{
           		int weightKg = ((Integer)info.get("weight")).intValue();
           		int passengerCount = ((Integer)info.get("passengers")).intValue();

     			AircraftBean[] aircraftData=data.getAircraftByRegistration(aircraft[0].getRegistration());
     			int additionalcrew = aircraftData[0].getCrew();
     			int crewseats;
     			double payLoad = aircraftData[0].getMaxWeight() - aircraftData[0].getEmptyWeight() - (77 * (1 + additionalcrew));
     			int payloadnow = (int)Math.round(payLoad - aircraftData[0].getTotalFuel() * Data.GALLONS_TO_KG);

     			if (additionalcrew > 0)
           			crewseats = 2;
     			else
           			crewseats = 1;
     			
     			int seats = (aircraftData[0].getSeats() - crewseats);
     			int seatsleft = (seats - passengerCount);
 				int weightleft = (payloadnow - weightKg + (77 * (1 + additionalcrew)));

     			if (weightleft > 0) 
     			{
%>
           			<p>Pilot, crew &amp; payload weight for next flight: 
           			<b><%=weightKg %> Kg</b>/<font color="#666666"><%=(int)Math.round(weightKg/0.45359237) %> Lb</font> (<font color="#003300"><%= weightleft %> Kg remains available at current fuel load</font>)
           			</p>
<% 				}
 				else if (weightleft == 0)
 				{
%>			        <p>Pilot, crew &amp; payload weight for next flight: 
					<b><%=weightKg %> Kg</b>/<font color="#666666"><%=(int)Math.round(weightKg/0.45359237) %> Lb</font> (<font color="#003300">Payload at weight limit for current fuel load</font>)
					</p>
<% 				}
 				else 
 				{
%>        			<p>Pilot, crew &amp; payload weight for next flight: 
					<b><%=weightKg %> Kg</b>/<font color="#666666"><%=(int)Math.round(weightKg/0.45359237) %> Lb</font> (<font color="#660000">Flight overweight by <%= (weightKg - payloadnow - (77 *(1 + additionalcrew))) %> Kg for current fuel load</font>)
					</p>
<% 				}

     			if (seatsleft > 1) 
     			{
%>         			<p>Passenger count for next flight: 
					<b><%= passengerCount%></b> (<font color="#003300"><%= seatsleft %>  seats available</font>) 
					</p>
<% 				}
 				else if (seatsleft == 1) 
 				{
%>		     		<p>Passenger count for next flight: 
					<b><%= passengerCount %></b>(<font color="#003300">1 seat available</font>) 
					</p>
<% 				}
 				else 	
 				{
%>					<p>Passenger count for next flight: 
					<b><%= passengerCount %></b>(<font color="#660000">No seats available</font>) 
					</p>
<% 				}
     		}
			assignmentsTotalAll = assignmentsTotalPay + assignmentsHoldPay;
			
			if(assignmentsHoldPay != 0)
			{
%>			<p>Total Value in Loading Area: <b><%=Formatters.currency.format(assignmentsTotalPay) %></b></p>
<%
			}
%>
			<p>Total Value of All Assignments: <b><%=Formatters.currency.format(assignmentsTotalAll) %></b></p>
			Total Number of Assignments: <b><%= assignments.length %></b>
     		<ul class="footer">
           	<li>Status:
           		<ul>
               		<li>Enroute: The assignment is currently enroute</li>
               		<li>Waiting for departure: The assignment will be flown next flight</li>
               		<li>Selected: The assignment will not be flown next flight</li>
           		</ul>
           	</li>
     		</ul>
<% 		}
%>
<div class="dataTable"> 
<%	
	if (aircraft.length == 0) 
	{ 
%>		<div class="message">No aircraft selected</div>
<%	} 
	else 
	{ 
%>
	   	<form method="post" action="userctl" name="aircraftForm" id="aircraftForm">
	   	<input type="hidden" name="event" value="Aircraft"/>
	   	<input type="hidden" name="type" value="remove"/>
	   	<input type="hidden" name="id" />
	   	<input type="hidden" name="reg" />
	   	<input type="hidden" name="hold" />
		<input type="hidden" name="returnpage" value="<%=returnPage%>"/>
	   	<table>
	        <caption>
	       	Aircraft - <input type="button" onclick="doSubmitX('<%=aircraft[0].getRegistration() %>', <%= user.getId()%>, <%= aircraft[0].getHoldRental()%>)" name="holdRental" value="<%=aircraft[0].getHoldRental() ? "Hold" : "Release"%>" /> when no assignments on board.
	        </caption>           
	     	<thead>
	     	<tr>
				<th>Registration</th><th>Type</th><th>Equipment</th><th>Location</th><th>Fuel</th><th>Rental Price</th><th>Action</th>
	     	</tr>
	     	</thead>
	     	<tbody>
<%
		boolean departed = aircraft[0].isDeparted();
		boolean wet = aircraft[0].wasWetRent();
		int price = wet ? aircraft[0].getRentalPriceWet() :
		aircraft[0].getRentalPriceDry();
		if (!departed) 
		{
			String icao = aircraft[0].getSLocation();
			AirportBean mapAirport = data.getAirport(icao);
			double latl = mapAirport.getLat();
			double lonl = mapAirport.getLon();
%>
		<script type="text/javascript">
			loc['plane'] = [];
			loc['plane'][0] = [];
			loc['plane'][0].icao = '<%=icao%>';
			loc['plane'][0].latl = <%=latl%>;
			loc['plane'][0].lonl = <%=lonl%>;
			loc['plane'][0].status = 'plane';
			var mapCenter = {latl: <%=latl%>, lonl: <%=lonl%>}
		</script>
		
<%			}
%>      	<tr>
           	<td><a class="normal" href="<%=response.encodeURL("aircraftlog.jsp?registration=" + aircraft[0].getRegistration()) %>"><%= aircraft[0].getRegistration()%></a></td>
           	<td><%= aircraft[0].getMakeModel() %></td>
           	<td><%= aircraft[0].getSEquipment() %></td>
           	<td><a class="link" href="<%=response.encodeURL("airport.jsp?icao=" + aircraft[0].getSLocation())%>"> <%= aircraft[0].getSLocation() %></a></td>
           	<td><%= Formatters.oneDigit.format(aircraft[0].getTotalFuel()) %> Gal(<%= (int)Math.round(100.0 *aircraft[0].getTotalFuel()/(double)aircraft[0].getTotalCapacity())%>%)</td>
           	<td><%= "$" + price + " " + (aircraft[0].getAccounting() == AircraftBean.ACC_TACHO ? "Tacho" : "Hour") + (wet ? "/Wet" : "/Dry")	%></td>
			<td>
<% 
		if (!departed)
	   	{
%>
<% 			if (!isAllInPresent)
       		{ 
%>     			<a class="link" href="javascript:doSubmit2('<%=aircraft[0].getRegistration() %>')">Cancel</a>
<%			}
%>     			<a class="link" href="<%=response.encodeURL("refuel.jsp?registration=" + aircraft[0].getRegistration()) %>">Refuel</a>
<% 		
			if (aircraft[0].isBroken() && aircraft[0].isAllowRepair())
    		{
%>         		<a class="link" href="<%= response.encodeURL("maintenance.jsp?registration=" + aircraft[0].getRegistration()) %>">Fix</a>
<%			}
		}
%>			</td>
			</tr>
     </tbody>
     </table>
     </form>
</div>
<div class="dataTable">
<%
	 AircraftBean[] aircraftData=data.getAircraftByRegistration(aircraft[0].getRegistration());
	 int additionalcrew = aircraftData[0].getCrew();
	 double fuelCap = aircraftData[0].getTotalCapacity();
	 double payLoad = aircraftData[0].getMaxWeight() - aircraftData[0].getEmptyWeight() - (77 * (1 + additionalcrew));
	 int fuelCapInt = (int)fuelCap;
	 int payload75 = (int)Math.round(payLoad - fuelCap * 0.75 * Data.GALLONS_TO_KG);
	 int payload100 = (int)Math.round(payLoad - fuelCap * Data.GALLONS_TO_KG);
	 int payloadnow = (int)Math.round(payLoad - aircraftData[0].getTotalFuel() * Data.GALLONS_TO_KG);
	 int crewseats;
	 if (additionalcrew > 0)
	     crewseats = 2;
	 else
	     crewseats = 1;
	 int seats = aircraftData[0].getSeats() - crewseats;
%>

     <table>
	     <caption>Fuel Calculations for <%=aircraftData[0].getRegistration()%></caption>
	     <tr>
	     	<td>Max Passenger/Cargo Payload with Current Fuel Load: <b><%=payloadnow %> Kg</b> or <b><%= Math.min(seats, (int)(payloadnow/77)) %> pax</b></td>
	     </tr>
	     <tr>
	     	<td>Max Allowable Gallons for <%=aircraftData[0].getRegistration()%>: <b><%= fuelCapInt %></b></td>
	     </tr>
	     <tr>
	     	<td>Max Allowable Passengers for <%=aircraftData[0].getRegistration()%>: <b><%= seats %></b></td>
	     </tr>
	     <tr><td>&nbsp;</td></tr>
	     <tr>
	     	<td><font color="#FF0033"><strong>CAUTION:</strong>  On larger aircraft, Max Fuel can be off by as much as 5 gallons due to floatingpoint issues.</font></td>
	     </tr>
     </table>
     <form>
	     <table>
		     <tr>
			     <td>Enter Payload (Kg):</td>
			     <td><input id="mypayload" class="textarea" value="0" size="5" maxlength="5" /></td>
			     <td># Pax:</td>
			     <td>
			     	<select id="mypax">
<%
    for (int c=0; c < seats +1; c++) 
    {
%>
						<option value="<%= c %>"><%= c %></option>
<%
	} 
%>	
     				</select>
     			</td>
     			<td>
     				<input id="calcFuel" type="button" class="button" value="Calculate" />
     			</td>
     			<td>Max Fuel:</td>
     			<td> <input id="mygallons" class="textarea" size="15" readonly /></td>
     		</tr>
     	</table>
     </form>
     <form>
     	<table>
     		<tr>
     			<td>Enter Fuel (Gal):</td>
     			<td><input id="entgallons" class="textarea" size="5" maxlength="5" /></td>
     			<td></td>
     			<td></td>
     			<td><INPUT id="calcLoad" TYPE="BUTTON" class="button" VALUE="Calculate"></td>
     			<td>Max Payload:</td>
     			<td><input id="expayload" class="textarea" size="16"readonly /></td>
     		</tr>
     	</table>
     </form>

<script>
$("#calcFuel").click(function () {
    pl = parseInt(Math.min($("#mypayload").val()));
    pax = parseInt($("#mypax").val());
    totalpayload = pax * 77 + pl;
    percent = Math.round(100 - (25 * (totalpayload - <%= payload100%>) / (<%= payload75 %> - <%= payload100  %>)));
    var cap = <%= fuelCap %> * percent / 100;
    maxgallons = cap;
    
    if (maxgallons <= 0)
    {
        $("#mygallons").val('Exceeds Limits');
    } 
    else
    {
        $("#mygallons").val(Math.min(maxgallons, <%= fuelCap %>) + ' gallons');
    }
});
$("#calcLoad").click(function ()
{
    percent = Math.min($("#entgallons").val() / <%= fuelCap %> * 100, 100);
    maxpayload = Math.floor((100 - percent) * ((<%= payload75 %> - <%= payload100 %>) / 25) + <%= payload100 %>);
    maxpax = Math.min(<%=seats%>, Math.floor(maxpayload / 77));
    
    if ($("#entgallons").val() > <%= fuelCap %>) {
        $("#expayload").val('Exceeds Limits');
    } 
    else {
        $("#expayload").val(maxpayload + ' Kg or ' + maxpax + ' pax');
    }
});
</script>

<% 
	} 
%>
</div>
</div>
</div>

<!--
	This is the lightbox markup, keep the JSP stuff all intact, and
	make sure that the aircraft	information table is between the 
	curly braces of the JSP conditional	clause 
-->
<div style="display: none;">
	<div style="width: 100%; height: 100%;" id="lb">
		<table style="height: 100px; width: 880px; vertical-align: middle;" border="1">
<%	
	if (aircraft.length > 0) 
	{ 
%>
		<tr>
			<th colspan="4">Aircraft information</th>
		</tr>
		<tr>
			<td style="width:210px;"><strong>Aircraft Type:</strong><br/><%=aircraft[0].getMakeModel() %></td>
			<td style="width:210px;"><strong>Reg #:</strong><br/><%=aircraft[0].getRegistration()%></td>
			<td style="width:210px;"><strong>Location:</strong><br/><%=aircraft[0].getSLocation()%></td>
			<td style="width:210px;"><strong>Current Fuel:</strong><br/><%=Formatters.oneDigit.format(aircraft[0].getTotalFuel()) %> Gal (<%=(int)Math.round(100.0 * aircraft[0].getTotalFuel()/(double)aircraft[0].getTotalCapacity())%>%)</td>
		</tr>
<%
	} 
%>
		<tr>
			<th colspan="4">Legend</th>
		</tr>
		<tr>
			<td><img src="https://maps.google.com/mapfiles/kml/pal2/icon48.png"/>Current Plane Location</td>
			<td><img src="https://google-maps-icons.googlecode.com/files/airport.png"/>Assignment Location</td>
			<td><img src="https://google-maps-icons.googlecode.com/files/airport-runway.png"/>Plane &amp; Assignment</td>
			<td><img src="https://maps.google.com/mapfiles/kml/pal2/icon56.png"/>Destination Airport</td>
		</tr>
		<tr>
			<td colspan="4">
				<input type="checkbox" value="draw-lines" id="draw-lines" checked/><strong>Show Lines</strong>
			</td>
		</tr>
		</table>
		<div id="map_canvas" style="width:900px; height:550px">
			<div style="width: 220px; height: 19px; margin: 250px auto 0 auto;">
				<img src="img/ajax-loader.gif" />
			</div>
		</div>
	</div>
</div>

</body>
</html>

