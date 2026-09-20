<div align="center">
  
  <h1>CreoleCast for Android</h1>
  <p><b><i>Any audio to any speaker on your network.</i></b></p>
</div>

<hr />

<h2>🚀 Supported Streaming Protocols</h2>
<p>We believe in open standards. CreoleCast allows you to stream to a wide array of devices:</p>

<ul>
  <li><b>AriaCast:</b> Protocol using binary WebSockets for low-latency, high-fidelity audio with rich metadata sync.</li>
  <li><b>snapcast:</b></li>
  <li><b>AirPlay 1:</b> Seamless streaming to legacy Apple devices and Hi-Fi speakers.</li>
  <li><b>AirPlay 2:</b> Encrypted streaming to Macs, Apple TVs and HomePods &mdash; HomeKit transient or PIN pairing with a persistent device identity, FairPlay SAP, an encrypted control channel, PTP/NTP timing and live metadata, artwork and volume sync.</li>
  <li><b>DLNA / UPnP:</b> Universal compatibility with Smart TVs, AV Receivers, and media boxes.</li>
</ul>

<hr />

<h2>✨ Key Features</h2>
<div style="background-color: #f6f8fa; padding: 15px; border-radius: 8px;">
  <ul>
    <li><b>System-Wide Capture:</b> Works with <i>any (No DRM)</i> app.</li>
    <li><b>Automatic Discovery:</b> Instant detection of AriaCast, snapcast, AirPlay and DLNA devices on your network.</li>
    <li><b>Rich Metadata Sync:</b> Pushes track title, artist, and album art to receivers in real-time.</li>
    <li><b>Quick Settings Tile:</b> Start casting directly from your notification shade.</li>
  </ul>
</div>
<hr />


<h2>🎵 Audio Source Compatibility</h2>
<p>
  CreoleCast captures audio at the system level via the <code>MediaProjection</code> API. 
  While this allows for broad compatibility, please note the following regarding content sources:
</p>

<div style="background-color: #fff3cd; padding: 15px; border-radius: 8px; border-left: 5px solid #ffc107; color: #856404;">
  <strong>Note on DRM-Protected Content:</strong> 
  Some major streaming services (like Spotify, YouTube Music, etc.) implement strict <code>FLAG_SECURE</code> 
  or DRM protections that prevent system-level audio capture. Consequently, CreoleCast may not be able 
  to stream audio from these specific apps by default.
  <br><br>
  <strong>CreoleCast shines with:</strong>
  <ul>
    <li><b>Local Music Players:</b> Perfect for Poweramp, Musicolet, or any player managing your personal FLAC/MP3 library.</li>
    <li><b>Podcasts & Audiobooks:</b> Great for AntennaPod or other open-source audio apps.</li>
    <li><b>Personal Media:</b> Your own voice recordings, local audio files, and non-DRM streaming apps.</li>
  </ul>
</div>

<p>
  <i>Advanced users can bypass some restrictions on rooted devices using the <a href="https://github.com/LSPosed/DisableFlagSecure">DisableFlagSecure</a> module with LSPosed/Magisk.</i>
</p>
