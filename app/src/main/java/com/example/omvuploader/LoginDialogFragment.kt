package com.example.omvuploader

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.example.omvuploader.databinding.DialogLoginBinding

class LoginDialogFragment : DialogFragment() {

    private var _binding: DialogLoginBinding? = null
    private val binding get() = _binding!!

    private var listener: OnCredentialsSavedListener? = null

    interface OnCredentialsSavedListener {
        fun onCredentialsSaved(server: String, username: String, password: String, shareName: String)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogLoginBinding.inflate(LayoutInflater.from(context))

        // Pre-llenar con las credenciales por defecto
        binding.serverInput.setText("192.168.0.3")
        binding.usernameInput.setText("pi")
        binding.passwordInput.setText("Elu1234")
        binding.shareNameInput.setText("RaspberryHDD")

        return AlertDialog.Builder(requireContext())
            .setTitle("Configuración del Servidor")
            .setView(binding.root)
            .setPositiveButton("Guardar") { _, _ ->
                val server = binding.serverInput.text.toString()
                val username = binding.usernameInput.text.toString()
                val password = binding.passwordInput.text.toString()
                val shareName = binding.shareNameInput.text.toString()

                if (server.isNotEmpty() && username.isNotEmpty() && password.isNotEmpty() && shareName.isNotEmpty()) {
                    listener?.onCredentialsSaved(server, username, password, shareName)
                }
            }
            .setNegativeButton("Cancelar", null)
            .create()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun setOnCredentialsSavedListener(listener: OnCredentialsSavedListener) {
        this.listener = listener
    }

    companion object {
        fun newInstance(): LoginDialogFragment {
            return LoginDialogFragment()
        }
    }
}