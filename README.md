Browser detects the protocol ws and sends additional header to switch to websocket from regular http and 

Adds additional Request header[Refer the SS below]
![image](https://github.com/user-attachments/assets/7b4ca536-88ff-4c1a-9756-64bfa5ee1c48)

The response for the upgradation is given below.
```
Connection: upgrade //Sending upgrade request initially with http
upgrade: websocket // indicated to which protocol
sec-websocket-accept: unique_identifier for new connection
```

Refer image: ![image](https://github.com/user-attachments/assets/cc69ecdb-2da7-4741-a503-c8c4ae703cee)

When Connect button is pressed the SS showing switch protocols and the http code for that is 101.

