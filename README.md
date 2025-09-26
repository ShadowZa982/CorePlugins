# CoreGuardian – Protect Your Server with Confidence

**CoreGuardian** is a lightweight yet powerful plugin designed to help protect your Minecraft server from disruptive players and malicious attacks. While no plugin can guarantee 100% protection, CoreGuardian provides a strong first line of defense and useful tools to keep your community safe.

I am Vietnamese, and I originally created this plugin for Vietnamese players. However, if you would like an English version, please don’t hesitate to send me a request, and I will work on updating the plugin.

For now, all messages and notifications are in Vietnamese, which might cause some inconvenience if you are not a Vietnamese speaker.


## ✨ Key Features

* **Essential Security Tools**
  CoreGuardian comes with a set of basic but effective features that make it easy for server owners to set up, configure, and manage. The commands are intuitive, user-friendly, and designed for quick access.

* **Discord Webhook Integration**
  Stay informed in real-time. CoreGuardian can send alerts directly to your Discord server when suspicious activity is detected — such as unauthorized login attempts, name impersonation, force-op exploits, UUID/IP spoofing, and more.

* **IP Reputation Checking (IPQualityScore)**
  CoreGuardian integrates with the **IPQualityScore API** to detect and block suspicious or high-risk IP addresses before they can cause trouble. This helps prevent VPN/proxy abuse and keeps unwanted traffic out.

* **Folia Support**
  Whether you’re running a traditional Spigot/Paper setup or using the next-generation **Folia** architecture, CoreGuardian is fully compatible.

* **Multi-Proxy Compatibility**
  The plugin supports multi-port and proxy server setups such as **BungeeCord**, **Velocity**, and others, ensuring flexibility for networks of all sizes.

## ⚠️ Important Note

CoreGuardian is a strong safeguard, but it is not an absolute shield. There’s always a small chance that determined attackers may find a way through. If that happens, consider it unfortunate luck rather than a flaw in the plugin itself.

## 🎯 Why Choose CoreGuardian?

If you’re looking for an easy-to-use, reliable plugin that adds an extra layer of protection to your Minecraft server while keeping you updated via Discord, CoreGuardian is the right choice.

Protect your server. Keep your players safe. Build with confidence.

# 📖 CoreGuardian Command Guide

This guide will help you understand and use all available commands from **CoreGuardian**.
There are two main command groups: **/blacklistcmd** (for managing blocked commands & IPs) and **/core** (for server security & OP management).

---

## 🔒 Blacklist Command

`/blacklistcmd` — Manage blacklisted commands and IPs.

* **/blacklistcmd list [page]**
  Show all blocked commands (with pagination).

* **/blacklistcmd add <command>**
  Add a command to the blacklist.
  *Example:* `/blacklistcmd add op`

* **/blacklistcmd remove <command>**
  Remove a command from the blacklist.
  *Example:* `/blacklistcmd remove kill`

* **/blacklistcmd listip**
  Show all blocked IP addresses.

* **/blacklistcmd addip <ip>**
  Add an IP to the blacklist.
  *Example:* `/blacklistcmd addip 123.45.67.89`

* **/blacklistcmd removeip <ip>**
  Remove an IP from the blacklist.
  *Example:* `/blacklistcmd removeip 123.45.67.89`

⚠️ Only **OPs** can run these commands.

---

## 🛡 Core Security Command

`/core` — Main command for server operators and administrators.

* **/core ipchange <name> <ip>** *(Console only)*
  Change the registered IP of an OP.

* **/core deop <name> <pass>** *(Console only)*
  Remove OP status from a player and delete their data.

* **/core op <name> <pass>** *(Console only)*
  Grant OP to a player and save their current IP.

* **/core opnew <pass>** *(Console only)*
  Set a new admin password for OP management.

* **/core unbanip <name|ip>**
  Remove a ban from an IP across all systems.

* **/core accept <player> <pass>**
  Approve an OP authentication request for a player.

* **/oppass <password>** *(Player command)*
  OPs must confirm their admin password to verify themselves.

* **/core oppass check <pass>** *(Console only)*
  Check if the admin password is valid.

* **/core reload**
  Reload the plugin configuration and Discord bot.

* **/core checkserver**
  Show basic server information (TPS, RAM usage, version, players online).

---

## ℹ️ Notes

* Commands marked with **(Console only)** cannot be used in-game.
* Make sure you have the required **permissions** (e.g., `coreplugin.reload`, `coreplugin.opaccept`, `coreplugin.coreunbanip`).
* If a player tries to use restricted commands/IPs, they will be blocked automatically.

---

✅ With these commands, you can secure your server against exploiters, force-op attempts, and malicious IPs.


