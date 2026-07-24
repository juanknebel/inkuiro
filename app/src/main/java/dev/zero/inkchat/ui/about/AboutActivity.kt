package dev.zero.inkchat.ui.about

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dev.zero.inkchat.BuildConfig
import dev.zero.inkchat.R
import dev.zero.inkchat.databinding.ActivityAboutBinding
import dev.zero.inkchat.ui.eink.EinkRefresh

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.txtVersion.text = getString(R.string.about_version, BuildConfig.VERSION_NAME)
        binding.txtEmail.setOnClickListener {
            val email = getString(R.string.author_email)
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))
                )
            }
        }
        binding.txtRepo.setOnClickListener {
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.author_repo)))
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        EinkRefresh.fullRefresh(binding.root)
    }
}
