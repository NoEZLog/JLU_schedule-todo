package cn.jlu.schedule.auth

/** 只在吉大 CAS 登录页执行：调用页面现有 login()，让网站自行加密并提交表单。 */
internal object CasWebLoginScript {
    fun build(credentials: JluCredentials): String {
        val studentId = jsString(credentials.studentId)
        val password = jsString(credentials.password)
        return """
            (function() {
              if (location.protocol !== 'https:' ||
                  location.hostname !== 'cas.jlu.edu.cn' ||
                  location.pathname !== '/tpass/login') return 'wrong-page';
              var form = document.getElementById('loginForm');
              var tab = document.getElementById('password_login');
              if (!form || !tab || typeof window.login !== 'function') return 'not-ready';
              tab.click();
              var user = document.getElementById('un');
              var pass = document.getElementById('pd');
              if (!user || !pass) return 'not-ready';
              user.value = $studentId;
              pass.value = $password;
              var remember = document.getElementById('rememberName');
              if (remember) remember.checked = false;
              window.login();
              return 'submitted';
            })()
        """.trimIndent()
    }

    private fun jsString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> if (char.code < 0x20) {
                    append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else append(char)
            }
        }
        append('"')
    }
}
