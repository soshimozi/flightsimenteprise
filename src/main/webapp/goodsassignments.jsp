<%@page language="java" contentType="text/html; charset=ISO-8859-1" %>
<%@ page import="net.fseconomy.beans.UserBean" %>
<%@ page import="net.fseconomy.data.Accounts" %>
<%@ page import="java.util.List" %>
<%@ page import="net.fseconomy.data.Groups" %>

<jsp:useBean id="user" class="net.fseconomy.beans.UserBean" scope="session"/>

<%
    if(!user.isLoggedIn())
    {
%>
<script type="text/javascript">document.location.href="/index.jsp"</script>
<%
        return;
    }

    int transferId;
    boolean isStaff;

    String sTransferId = request.getParameter("transferid");

    if(sTransferId == null)
        transferId = user.getId();
    else
        transferId = Integer.parseInt(sTransferId);

    //check if proper access
    if(user.getId() != transferId)
    {
        int role = Groups.getRole(transferId, user.getId());
        if (role < UserBean.GROUP_MEMBER)
            return;

        isStaff = role >= UserBean.GROUP_STAFF;
    }
    else
    {
        isStaff = true;
    }

    //setup return page if action used
    String params = "?transferid=" + transferId;
    String returnPage = request.getRequestURI() + params;
%>

<!DOCTYPE html>
<html lang="en">
<head>

    <title>FSEconomy terminal</title>

    <meta http-equiv="X-UA-Compatible" content="IE=edge"/>
    <meta http-equiv="Content-Type" content="text/html;charset=utf-8"/>

    <link rel='stylesheet prefetch' href='//maxcdn.bootstrapcdn.com/bootstrap/3.3.1/css/bootstrap.min.css'>
    <link rel='stylesheet prefetch' href='//maxcdn.bootstrapcdn.com/bootstrap/3.3.1/css/bootstrap-theme.min.css'>
    <link rel="stylesheet" type="text/css" href="css/redmond/jquery-ui.css"/>
    <link rel="stylesheet" type="text/css" href="fancybox/jquery.fancybox-1.3.1.css"/>
    <link rel="stylesheet" type="text/css" href="css/tablesorter-style.css"/>
    <link rel="stylesheet" type="text/css" href="css/Master.css"/>

    <% //regressed jquery so that lightbox would work %>
    <script src="//ajax.googleapis.com/ajax/libs/jquery/2.1.1/jquery.min.js"></script>
    <script src="//ajax.googleapis.com/ajax/libs/jqueryui/1.11.2/jquery-ui.min.js"></script>
    <script src="//maxcdn.bootstrapcdn.com/bootstrap/3.3.1/js/bootstrap.min.js"></script>
    <script src="https://maps.google.com/maps/api/js?sensor=false"></script>
    <script src="scripts/jquery.cookie.js"></script>

    <script type='text/javascript' src='scripts/jquery.tablesorter.js'></script>
    <script type='text/javascript' src="scripts/jquery.tablesorter.widgets.js"></script>
    <script type='text/javascript' src='scripts/parser-checkbox.js'></script>
    <script type='text/javascript' src='scripts/parser-timeExpire.js'></script>
    <script src="scripts/AutoComplete.js"></script>

    <script type="text/javascript">

        $(function () {
            initAutoComplete("#selectedGroupName", "#selectedGroupId", <%= Accounts.ACCT_TYPE_GROUP %>);
        });

    </script>

    <script type="text/javaScript">

        function checkAll() {
            var field = document.getElementById("assignmentForm").select;
            for (i = 0; i < field.length; i++) {
                if (!field[i].disabled)
                    field[i].checked = true;
            }
            field.checked = true;  // needed in case of only one box
        }

        function uncheckAll() {
            var field = document.getElementById("assignmentForm").select;
            for (i = 0; i < field.length; i++)
                field[i].checked = false;

            field.checked = false;  // needed in case of only one box
        }

        function addToMyFlight() {
            var form = document.getElementById("assignmentForm");
            form.id.value = $("input[name='select']:checked");
            form.action = "<%= response.encodeURL("userctl") %>";
            form.submit();
        }

        function unlockAssignments(id) {
            var form = document.getElementById("assignmentForm");
            form.action = "<%= response.encodeURL("userctl") %>";
            form.event.value = "unlockGoodsAssignment";
            form.submit();
        }

        function deleteGoodsAssignments() {
            var form = document.getElementById("assignmentForm");
            form.action = "<%= response.encodeURL("userctl") %>";
            form.event.value = "deleteGoodsAssignment";
            form.submit();
        }

        function transferAssignment(id, id2) {
            var form = document.getElementById("assignmentForm");
            form.id.value = id;
            if (id2 != 0) {
                form.groupid.value = id2;
                form.action = "<%= response.encodeURL("userctl") %>";
                form.type.value = "move";
            }
            form.submit();
        }

        function addComment() {
            var checkedItems = $("input[name='select']:checked");
            if (!isOneOrMoreChecked(checkedItems)) {
                alert("No assignments are selected");
                return;
            }

            var comment = $("#assignmentComment").val();
            if (comment == "") {
                if (!confirm("The comment is blank, are you sure you want to reset the selected assignments?"))
                    return;
            }

            var form = document.getElementById("assignmentForm");
            form.id.value = checkedItems;
            form.comment.value = comment;
            form.action = "<%= response.encodeURL("userctl") %>";
            form.type.value = "comment";
            form.submit();
        }

        function isOneOrMoreChecked(checkboxes) {
            var okay = false;

            for (var i = 0, l = checkboxes.length; i < l; i++) {
                if (checkboxes[i].checked) {
                    okay = true;
                }
            }
            return okay;
        }

        var displayGroupsOnly = false;

        function setGroupsOnly(flag)
        {
            var ac = document.getElementById('byAutoComplete');
            var bg = document.getElementById('byGroup');
            var bgcb = document.getElementById('groupsOnly');

            if(flag){
                bg.style.display = 'block';
                ac.style.display = 'none';
                bgcb.checked = true;
            }
            else
            {
                ac.style.display = 'block';
                bg.style.display = 'none';
                bgcb.checked = false;
            }
        }

        function doGroupsOnly()
        {
            var ac = document.getElementById('byAutoComplete');
            var bg = document.getElementById('byGroup');
            if(ac.style.display == 'none'){
                ac.style.display = 'block';
                bg.style.display = 'none';
                $.cookie('displayGroupsOnly', false);
            }
            else
            {
                bg.style.display = 'block';
                ac.style.display = 'none';
                $.cookie('displayGroupsOnly', true);
            }
        }

    </script>

    <script type="text/javascript">

        $(function () {

            $.extend($.tablesorter.defaults, {
                widthFixed: false,
                widgets: ['zebra', 'columns']
            });

            $('.assignmentTable').tablesorter();

            $('.tdClick').click(function (event) {
                if (event.target.type !== 'checkbox') {
                    $(':checkbox', this).trigger('click');
                }
            });

            $('.editassignment').click(function () {
                var id = this.getAttribute("data-id");

                $("#assignmentData").load("editassignmentdata.jsp?assignmentid=" + id);

                $("#myModal").modal('show');
            });


            $('.newassignment').click(function () {
                var id = this.getAttribute("data-id");

                $("#assignmentData").load("editnewassignmentdata.jsp", function () {
                    newAssignmentInit(<%=transferId%>, '<%=returnPage%>');
                });

                $("#myModal").modal('show');
            });

            $("#transferButton").click(
                    function () {
                        if (window.confirm("Are you sure you want to transfer selected assignments to " + $("#transfername").val() + "?")) {
                            if(displayGroupsOnly)
                                transferAssignment($("input[name='select']:checked"), $("#selectedGroupId").val());
                            else
                                transferAssignment($("input[name='select']:checked"), $("#selectedGroupId").val());
                        }
                    }
            );

            displayGroupsOnly = $.cookie('displayGroupsOnly') === 'true';
            setGroupsOnly(displayGroupsOnly);

            $("#groupSelect").change(function() {
                $("#groupSelect").find("option:selected" ).each(function() {
                    $("#selectedGroupName").val($(this).text());
                    $("#selectedGroupId").val($(this).val());
                });
            })

        });

        var loc = {};
        var assignment = {};
        var i = 0;
        var returnUrl = '<%=returnPage%>';

    </script>

</head>
<body>

<jsp:include flush="true" page="top.jsp"/>
<jsp:include flush="true" page="menu.jsp"/>

<div id="wrapper">
    <div class="content">
        <jsp:include flush="true" page="assignmentsgoodsdata.jsp">
            <jsp:param name="transferid" value="<%=transferId%>"/>
            <jsp:param name="returnPage" value="<%=returnPage%>"/>
        </jsp:include>

        <br>
        <a href="gmapfull.jsp?type=transfer&id=<%=transferId%>" target="_blank">Map Transfer Assignments</a>
        <br>
        <a class="btn btn-default" href="javascript:checkAll()">Select All</a>
        <a class="btn btn-default" href="javascript:uncheckAll()">De-Select</a>
        <div>
            <input class="btn btn-success" type="button" name="add_Selected" value="Add Selected to My Flight"
                   onclick="addToMyFlight()"/>
            <br/><br/>
<%
    if (isStaff)
    {
%>
            <div class="panel panel-primary alert-info" style="background: lightsteelblue; padding: 15px">
                <h3>Owner and Staff Only</h3>
                <div class="well">
                    <h4>Assignment Actions</h4>
                    <input class="btn btn-warning" type="button" name="unlock_Selected" value="Unlock Selected" onclick="unlockAssignments()"/>
                    <input class="btn btn-danger" type="button" name="delete_Selected" value="Delete assignments" onclick="deleteGoodsAssignments()"/>
                </div>
                <div class="well alert-danger">
                    <h4>Add Comment to selected assignments</h4>
                    <strong>Warning:</strong> This will overwrite any existing comment for the selected assignments!<br/>
                    <label>
                        You must click the "Add comment" button!<br/>
                        <input class="form-control" id="assignmentComment" type="text" size="65" maxlength="250">
                    </label>
                    <input class="btn btn-primary" type="button" value="Add Comment" onclick="addComment()"/><br/>
                </div>
                <div class="well">
                    <h4>Transfer selected assignments to group:</h4>
                    <form class="form-horizontal">
                        <div class="formgroup">
                            <label><input type="checkbox" id="groupsOnly" onclick="doGroupsOnly();"> Limit to my groups only</label>
                        </div>
                        <div class="formgroup">
                            <label>Transfer to:
                                <span id="byAutoComplete">
                                    <input class="form-control" type="text" id="selectedGroupName" name="selectedGroupName"/>
                                    <input type="hidden" id="selectedGroupId" name="selectedGroupId" value=""/>
                                </span>
                                <span id="byGroup" style="display: none">
                                    <select class="form-control" id="groupSelect">
                                        <option value=""></option>
<%
    List<UserBean> groups = Accounts.getGroupsForUser(user.getId());

    for (UserBean group : groups)
    {
        if (user.groupMemberLevel(group.getId()) >= UserBean.GROUP_STAFF)
        {
%>
                                        <option value="<%=group.getId()%>"><%= group.getName()%></option>
<%
        }
    }
%>
                                    </select>
                                </span>
                            </label>
                            <input class="btn btn-primary" type="button" id="transferButton" name="transferButton" value="Transfer"/>
                        </div>
                    </form>
                </div>
            </div>
        </div>
<%
    }
%>
    </div>
</div>

<!-- Modal HTML -->
<div id="myModal" class="modal fade">
    <div class="modal-dialog">
        <div class="modal-content ui-front">
            <div id="assignmentData">

            </div>
        </div>
    </div>
</div>

</body>
</html>
