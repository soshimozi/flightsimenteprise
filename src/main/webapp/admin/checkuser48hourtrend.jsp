<%@page language="java"
        contentType="text/html; charset=ISO-8859-1"
        import="net.fseconomy.data.*"
%>
<%@ page import="net.fseconomy.beans.UserBean" %>
<%@ page import="net.fseconomy.dto.TrendHours" %>

<jsp:useBean id="user" class="net.fseconomy.beans.UserBean" scope="session" />

<%
    if (!Accounts.needLevel(user, UserBean.LEV_MODERATOR))
    {
%>
        <script type="text/javascript">document.location.href="index.jsp"</script>
<%
        return;
    }
%>

<!DOCTYPE html>
<html lang="en">
<head>

    <title>FSEconomy terminal</title>

    <meta http-equiv="X-UA-Compatible" content="IE=edge" />
    <meta http-equiv="Content-Type" content="text/html;charset=utf-8"/>

    <link rel="stylesheet" type="text/css" href="../css/redmond/jquery-ui.css">
    <link href="../css/Master.css" rel="stylesheet" type="text/css" />

    <script type='text/javascript' src='/scripts/common.js'></script>
    <script type='text/javascript' src='/scripts/css.js'></script>
    <script type='text/javascript' src='/scripts/standardista-table-sorting.js'></script>
    <script type='text/javascript' src="../scripts/jquery.min.js"></script>
    <script type='text/javascript' src="../scripts/jquery-ui.min.js"></script>
    <script type='text/javascript' src="../scripts/AutoComplete.js"></script>

    <script type="text/javascript">

        $(function()
        {
            initAutoComplete("#username", "#user", <%= Accounts.ACCT_TYPE_PERSON %>);
        });

    </script>

</head>
<body>

<jsp:include flush="true" page="../top.jsp" />
<jsp:include flush="true" page="../menu.jsp" />

<div id="wrapper">
<div class="content">
<%
    String message = (String) request.getAttribute("message");
    if (message != null)
    {
%>
    <div class="message"><%= message %></div>
<%
    }
%>
<%
    if (request.getParameter("submit") == null && (message == null))
    {
%>	<h2>Enter User Account</h2>
	<div class="form" style="width: 400px">
	<form method="post">
	<tr>
	<td>
	Account Name : 
	    <input type="text" id="username" name="username"/>
	    <input type="hidden" id="user" name="user"/>
	</td>
	</td>
	</tr>
	<br/>
	<input type="submit" class="button" value="GO" />
	<input type="hidden" name="submit" value="true"/>
	<input type="hidden" name="return" value="/admin/adminuser48hourtrend.jsp"/>
	</form>
	</div>
<%
    }
    else if (request.getParameter("submit") != null)
    {
        UserBean inputuser = Accounts.getAccountByName(request.getParameter("username"));
        if (inputuser == null)
        {
            message = "User Not Found";
        }

        TrendHours[] trend = null;
        try
        {
            trend = Data.getTrendHoursQuery(inputuser.getName());
        }
        catch(DataError e)
        {
            message = "Error retrieving trend hours.";
        }

        if (message != null)
        {
%>	<div class="message"><%= message %></div>
<%
        }
        else if (inputuser != null)
        {
%>		<div class="dataTable">	
		<h2>User - <%= inputuser.getName() %> - 48 Hour Trend - Last 500 Flights</h2><br/>
		<a href="/admin/admin.jsp">Return to Admin Page</a><br/>
		<table id="sortableTableStats" class="sortable">
		<thead>
		<tr>
			<th>Date</th>
			<th>Duration</th>
			<th>Last 48 Hours</th>
			<th>**Over 30 Hours</th>
		</tr>
		</thead>
		<tbody>
<%
            for (int c=0; c < trend.length; c++)
            {
%>
            <tr>
			<td><%= trend[c].logdate %></td>
			<td><%= trend[c].duration %></td>
			<td><%= ((trend[c].last48Hours > 20.0) ? "<HTML><font color=Red><b>" : "") + trend[c].last48Hours + ((trend[c].last48Hours > 20.0) ? "</font></HTML></b>" : "") %></td>
		   	<td><%= ((trend[c].last48Hours > 30.0) ? "<b>**</b>" : "") %></td>	
			</tr>
<%
            }
%>
	</tbody>
	</table>
</div>
<%
        }
    }
%>
</div>
</div>
</body>
</html>
