<!DOCTYPE html SYSTEM "http://www.thymeleaf.org/dtd/xhtml1-strict-thymeleaf-4.dtd">
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:th="http://www.thymeleaf.org">
<head>
    <title>Process Image</title>
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
</head>

<body>

<table cellpadding="5">
  <tbody>
    <tr>
      <td>Instance Id:</td><td>${instanceId}</td>
    </tr>
    <tr>
      <td>Correlation Key:</td><td>${correlationKey}</td>
    </tr>
    <tr>
      <td>Process Id:</td><td>${processId}</td>
    </tr>
    <tr>
      <td>Status:</td><td>${status}</td>
    </tr>
    <tr>
      <td>Start Date:</td><td>${startDate}</td>
    </tr>
    <tr>
      <td>End Date:</td><td>${endDate}</td>
    </tr>
    <tr>
      <td>Duration:</td><td>${duration}</td>
    </tr>
    <tr><td colspan="2">Incident:</td></tr>
    <tr>
      <td>&nbsp;&nbsp;&nbsp;&nbsp;Assignments Retries:</td><td>${assignments_retries}</td>
    </tr>
  </tbody>
</table>

${image}

</body>

</html>