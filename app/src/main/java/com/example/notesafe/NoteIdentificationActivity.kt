package com.example.notesafe

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class NoteIdentificationActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note_identification)

        // Open RBI website
        val btnViewRbiWebsite: Button = findViewById(R.id.btnViewRbiWebsite)
        btnViewRbiWebsite.setOnClickListener {
            val uri = Uri.parse("https://paisaboltahai.rbi.org.in")
            val intent = Intent(Intent.ACTION_VIEW, uri)
            startActivity(intent)
        }
    }
}