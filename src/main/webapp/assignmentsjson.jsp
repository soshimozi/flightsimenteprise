<%@ page import="net.fseconomy.beans.AssignmentBean" %>
<%@ page import="net.fseconomy.beans.UserBean" %>
<%@ page import="com.google.gson.Gson" %>
<%@ page import="java.util.*" %>
<%@ page import="net.fseconomy.util.Formatters" %>
<%@ page import="net.fseconomy.beans.AircraftBean" %>
<%@ page import="net.fseconomy.dto.*" %>
<%@ page import="net.fseconomy.data.*" %>
<%@page language="java" contentType="text/html; charset=ISO-8859-1" %>
<jsp:useBean id="user" class="net.fseconomy.beans.UserBean" scope="session"/>
<%
    String type = request.getParameter("type");
    String sId = request.getParameter("id");
    String output;
    int role;

    List<AssignmentBean> assignments;
    MapAircraftInfo aircraftInfo = null;

    try
    {
        switch (type)
        {
            case "group":
                if (sId == null)
                    throw new Exception("Missing GroupId");

                int groupId = Integer.parseInt(sId);

                //check if proper access
                role = Groups.getRole(groupId, user.getId());
                if (role < UserBean.GROUP_MEMBER)
                    throw new Exception("No permission");

                assignments = Assignments.getAssignmentsForGroup(groupId, true);
                break;
            case "myflight":
                assignments = Assignments.getAssignmentsForUser(user.getId());
                AircraftBean acbean = Aircraft.getAircraftForUser(user.getId());
                if (acbean != null && acbean.getLocation() != null)
                {
                    AirportInfo airport = Airports.cachedAPs.get(acbean.getLocation());
                    aircraftInfo = new MapAircraftInfo(acbean.getLocation(), airport.latlon, acbean.getRegistration(), acbean.getMakeModel(), acbean.getSEquipment(), (int) (acbean.getTotalFuel() + .5) + " of " + acbean.getTotalCapacity() + " Gals", "", "");
                }
                break;
            case "transfer":
                if (sId == null)
                    throw new Exception("Missing TransferId");

                int transferId = Integer.parseInt(sId);

                //check if proper access
                UserBean goodsOwner = Accounts.getAccountById(transferId);
                if (goodsOwner.isGroup())
                {
                    role = Groups.getRole(transferId, user.getId());
                    if (role < UserBean.GROUP_MEMBER)
                        throw new Exception("No permission");
                }
                else if (transferId != user.getId())
                    throw new Exception("No permission");

                assignments = Assignments.getAssignmentsForTransfer(transferId);
                break;
            default:
                throw new Exception("Invalid parameters.");
        }

        //List<MapAssignments> mapList = new ArrayList<>();
        MapAssignments mapAssignments = null;
        MapData mapData = new MapData();
        mapData.mapAircraftInfo = null;
        mapData.mapAssignments = new ArrayList<>();

        String lastLocation = "";
        String lastDestination = "";
        boolean locChanged = false;
        boolean destChanged = false;
        AirportInfo depart = null;
        AirportInfo dest = null;

        for (AssignmentBean assignment : assignments)
        {
            if(assignment.getActive() == Assignments.ASSIGNMENT_HOLD)
                continue;

            //has location changed
            if(!assignment.getLocation().equals(lastLocation))
            {
                locChanged = true;
                lastLocation = assignment.getLocation();
                depart = Airports.cachedAPs.get(lastLocation);
            }

            //has destination changed
            if(!assignment.getTo().equals(lastDestination))
            {
                destChanged = true;
                lastDestination = assignment.getTo();
                dest = Airports.cachedAPs.get(lastDestination);
            }

            String distance = Integer.toString(Airports.findDistance(lastLocation, lastDestination)) + " NM";
            MapAssignment mapAssignment = new MapAssignment(lastDestination, assignment.getSCargo(), Formatters.currency.format(assignment.getRealPay()), distance);

            if(locChanged)
            {
                if(mapAssignments != null)
                    mapData.mapAssignments.add(mapAssignments);

                mapAssignments = new MapAssignments(depart);
            }

            if(destChanged)
                mapAssignments.destinations.add(dest);

            mapAssignments.assignments.add(mapAssignment);

            locChanged = false;
            destChanged = false;
        }

        mapData.mapAssignments.add(mapAssignments);
        if(aircraftInfo != null)
            mapData.mapAircraftInfo = aircraftInfo;

        response.setContentType("application/json");
        Gson gson = new Gson();
        output = gson.toJson(mapData);

    }
    catch (Exception e)
    {
        response.setStatus(400);
        output = "Error: " + e.getMessage();
        System.out.println(output);
    }
%><%= output %>