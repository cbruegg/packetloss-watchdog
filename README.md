# Packetloss Watchdog
With the rollout of DOCSIS 3.1, many users of the Vodafone Station supplied by Vodafone Germany are facing high packet loss occasionally. After contacting Vodafone's support [(which you should also complain to)](http://twitter.com/vodafoneservice), I was told that the device's RAM was full and that a restart would fix the issue. Unfortunately, it only takes a couple of days until the issue returns.

This tool continously monitors your connection's packet loss and restarts the Vodafone Station at any desired time.

## Supported Systems

The tool is written for the Java Virtual Machine but relies on the `ping` utility specific to Linux. Windows is supported if [WSL](https://docs.microsoft.com/de-de/windows/wsl/install-win10) is installed. Feel free to submit a PR to improve OS support. Personally, I'm running this tool on a Raspberry Pi.

## Download
You can find the latest release [here](https://github.com/cbruegg/packetloss-watchdog/releases/latest).

## Usage
### Example
```
PLWD_ROUTER_PASSWORD=xyz java -jar packetloss-watchdog.jar
```
All options are configured through environment variables. Except for `PLWD_ROUTER_PASSWORD`, they're all optional. By default, the above command will measure the packet loss every 30 minutes by pinging 1.1.1.1 for 3 minutes. If the packet loss exceeds 4 %, a restart of the Vodafone Station will be scheduled for 05:00 (am). Note that the tool needs to remain active for the restart to be actually performed.

If the packet loss returns to normal after 4 measurements in a row, any pending restart is canceled. This is to prevent unnecessary restarts when the packet loss recovers on its own.

When the restart is due, the tool will connect to 192.168.0.1 with the supplied password to perform the restart. Measurements will be resumed 30 minutes after the restart to prevent false measurements.

### Configuration Options

```
-h: Print this message
-v: Verbose output

Environment variables:
PLWD_ROUTER_IP:                                  Vodafone Station address. (Defaults to 192.168.0.1)
PLWD_ROUTER_PASSWORD:                            Vodafone Station admin password.
PLWD_PING_TARGET:                                The host to measure the packet loss with. (Defaults to 1.1.1.1)
PLWD_DURATION_BETWEEN_MEASUREMENTS_MS:           Delay between measurements. (Defaults to 30.0m)
PLWD_TOO_HIGH_THRESHOLD:                         If this packet loss value is exceeded, a router restart is scheduled. (Defaults to 0.04 for 4 % Packet Loss)
PLWD_RESTART_TIME:                               The time of day at which the router should be restarted. (Defaults to 05:00)
PLWD_CANCEL_PENDING_AFTER_NORMAL_MEASUREMENTS:   After this number of non-exceeding measurements, any pending router restart is canceled. (Defaults to 4)
PLWD_MEASUREMENT_DELAY_AFTER_RESTART_MS:         The duration to wait until measurements should be resumed after a router restart. (Defaults to 30.0m)
```

## Issues
*This tool hasn't been tested thoroughly, so you may encounter bugs.* As I hope that Vodafone will eventually fix the issue so this workaround is no longer needed, not much effort was spent on writing clean code. However, if you stumble upon any issue, feel free to contribute a fix. If that's not possible, you may open an issue on GitHub, though I can't make any promises to fix it. Please prefer to [complain to Vodafone](http://twitter.com/vodafoneservice).

## Registering the Tool as a Service
If running on Linux (e.g. on a Raspberry Pi), I'd recommend to use the following template. Place it in `/etc/systemd/system/packetloss-watchdog.service`.

```
[Unit]
Description=Packetloss Watchdog
After=multi-user.target

[Service]
Type=idle
# Set the right path here
ExecStart=/usr/bin/java -jar /path/to/packetloss-watchdog.jar -v
# Set the right user here
User=pi
# Set the right router password here
Environment=PLWD_ROUTER_PASSWORD=YOURROUTERSPASSWORD
# You can add further environment variables like this:
# Environment=PLWD_ROUTER_IP=192.168.86.1
Restart=always

[Install]
WantedBy=multi-user.target
```

Then, start and enable the service to make it run on boot:
```
systemctl start packetloss-watchdog
systemctl enable packetloss-watchdog
```

## How is the Restart Implemented?
This tool emulates the web interface's AJAX API calls. The original code uses some strange encryption that does not actually seem to improve security as requests are not signed. Unfortunately this made it a bit more difficult to send a valid restart request to the Vodafone Station as I couldn't just copy the encryption code for copyright reasons. Instead, the tool downloads the JavaScript code used for encryption and executes it in a JavaScript sandbox. This solution is inherently a bit fragile and may break on firmware updates - although I hope that a firmware update would also include a fix for the packet loss issue itself.
