<%@page language="java"
        contentType="text/html; charset=ISO-8859-1"
        import="net.fseconomy.data.*, net.fseconomy.util.Formatters"
%>
<%
    Data data = (Data)application.getAttribute("data");
%>
<jsp:useBean id="user" class="net.fseconomy.data.UserBean" scope="session"></jsp:useBean>
<%
    String icao = request.getParameter("icao");

    String SfacilityId = request.getParameter("facilityId");
    int facilityId = -1;
    boolean madeFacilitySelection = SfacilityId != null;

    int blocks = -1;
    int occupantId = -1;
    int suppliedDays = 0;
    boolean madeBlocksSelection = false;

    FboBean fbo = null;
    AirportBean airport = data.getAirport(icao);
    data.fillAirport(airport);
    FboFacilityBean[] facilities = data.getFboDefaultFacilitiesForAirport(icao);
    FboFacilityBean facility = null;

    if (madeFacilitySelection)
    {
        facilityId = Integer.parseInt(SfacilityId);
        for (int i = 0; i < facilities.length; i++)
        {
            if (facilities[i].getId() == facilityId)
            {
                facility = facilities[i];
                break;
            }
        }

        fbo = data.getFbo(facility.getFboId());
        suppliedDays = data.getGoodsQty(fbo, GoodsBean.GOODS_SUPPLIES) / fbo.getSuppliesPerDay(airport);
        madeBlocksSelection = request.getParameter("selectBlocks") != null;

        if (madeBlocksSelection)
        {
            blocks = Integer.parseInt(request.getParameter("blocks"));
            occupantId = Integer.parseInt(request.getParameter("occupantId"));
        }
    }
%>
<!DOCTYPE html>
<html lang="en">
<head>

    <title>FSEconomy terminal</title>

    <meta http-equiv="X-UA-Compatible" content="IE=edge" />
    <meta http-equiv="Content-Type" content="text/html;charset=utf-8"/>

    <link href="theme/Master.css" rel="stylesheet" type="text/css" />

</head>
<body>
<jsp:include flush="true" page="top.jsp" />
<jsp:include flush="true" page="menu.jsp" />
<div id="wrapper">
<div class="content">
	<div class="dataTable">
<%	
	if ((facilities.length == 0) || (madeFacilitySelection && (facility == null)))  
	{ 
%>
	<div class="message">No facilities available.</div>
<%  } 
	else if (!madeFacilitySelection) 
	{
%>	
	<table>
		<caption><%= airport.getIcao() %> - Passenger Facilities for Rent</caption>
		<thead>
			<tr>
				<th>FBO</th>
				<th>Gates available</th>
				<th>Rent</th>
				<th>Action</th>
			</tr>
		</thead>
		<tbody>
<%
		for (int i = 0; i < facilities.length; i++)
		{ 
			if (facilities[i].getUnits() == AssignmentBean.UNIT_PASSENGERS)
			{
				fbo = data.getFbo(facilities[i].getFboId());
				int spaceAvailable = data.calcFboFacilitySpaceAvailable(facilities[i], fbo, airport);
				String rentURL = "fbofacilityrent.jsp?icao=" + airport.getIcao() + "&facilityId=" + facilities[i].getId();
				String rentLink = "<a href=\"" + rentURL + "\">Rent</a>";
%>
			<tr>
				<td><%= fbo.getName() %></td>
				<td><%= spaceAvailable %> gates</td>
				<td><%= Formatters.currency.format(facilities[i].getRent()) %></td>
				<td><%= spaceAvailable < 1 ? "" : rentLink %></td>
			</tr>
<%
			}
		}
%>
		</tbody>
	</table>
<%
	} 
	else if (!madeBlocksSelection) 
	{
		Data.groupMemberData[] staffGroups = user.getStaffGroups();
		int spaceAvailable = data.calcFboFacilitySpaceAvailable(facility, fbo, airport);
%>
	<form method="post" action="fbofacilityrent.jsp" name="rentForm">
	<input type="hidden" name="icao" value="<%= icao %>" />
	<input type="hidden" name="facilityId" value="<%= facilityId %>" />
	
	<table>
	<caption>Renting Passenger Facilities from <%= airport.getIcao() %> - <%= fbo.getName() %></caption>
	<tbody>
		<tr>
			<td>Supplies</td><td><%= suppliedDays > 14 ? suppliedDays + " days" : "<span style=\"color: red;\">" + suppliedDays + " days</span>" %></td>
		</tr>
		<tr>
			<td>Monthly price per gate</td>
			<td><%= Formatters.currency.format(facility.getRent()) %></td>
		</tr>
		<tr>
			<td>Select number of gates</td>
			<td>
				<select class="formselect" name="blocks">
<%
		for (int i = 1; i <= spaceAvailable; i++)
		{
%>
				<option value="<%= i %>"<%= i == 1 ? " selected='selected' " : "" %>><%= i %> gates</option>
<%
		}
%>
				</select>
			</td>
		</tr>
		<tr>
			<td>Select Renter</td>
			<td>
				<select class="formselect" name="occupantId">
				<option value="<%= user.getId() %>" selected="selected" ><%= user.getName() %></option>
<%
		for (int i = 0; i < staffGroups.length; i++)
		{
%>
				<option value="<%= staffGroups[i].groupId %>" ><%= staffGroups[i].groupName %></option>
<%
		}
%>
				</select>
			</td>
		</tr>
		<tr>
			<td>&nbsp;</td>
			<td>
				<input name="selectBlocks" type="submit" class="button" value="Continue" />
			</td>
		</tr>
	</tbody>
	</table>
	</form>
<%
	} 
	else 
	{
		UserBean occupant = data.getAccountById(occupantId);
%>
	<form method="post" action="userctl" name="rentForm">
	<input type="hidden" name="event" value="rentFboFacility" />
	<input type="hidden" name="facilityId" value="<%= facilityId %>" />
	<input type="hidden" name="blocks" value="<%= blocks %>" />
	<input type="hidden" name="occupantId" value="<%= occupantId %>" />
	<input type="hidden" name="return" value="fbofacility.jsp?id=<%= occupantId %>" />
	
	<table>
	<caption>Renting Passenger Facilities from <%= airport.getIcao() %> - <%= fbo.getName() %></caption>
	<tbody>
		<tr>
			<td>Supplies</td><td><%= suppliedDays > 14 ? suppliedDays + " days" : "<span style=\"color: red;\">" + suppliedDays + " days</span>" %></td>
		</tr>
		<tr>
			<td>Monthly price per gate</td>
			<td><%= Formatters.currency.format(facility.getRent()) %></td>
		</tr>
		<tr>
			<td>Selected number of gates</td>
			<td><%= blocks %> gates</td>
		</tr>
		<tr>
			<td>Renter</td>
			<td><%= occupant.getName() %></td>
		</tr>
		<tr>
			<td>Total monthly rent</td>
			<td><b><%= Formatters.currency.format(facility.getRent() * blocks)  %></b></td>
		</tr>
		<tr>
			<td>&nbsp;</td>
			<td>
				<input name="confirmRent" type="submit" class="button" value="Confirm" />
			</td>
		</tr>
	</tbody>
	</table>
	</form>
<%
	}
%>
	</div>
</div>
</div>
</body>
</html>
