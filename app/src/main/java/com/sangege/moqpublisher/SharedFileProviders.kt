package com.sangege.moqpublisher

import androidx.core.content.FileProvider

// Android caches local providers by component class. Each authority must have
// its own component so the installer never receives the diagnostics path map.
class AppUpdateFileProvider : FileProvider()
class DiagnosticsFileProvider : FileProvider()
