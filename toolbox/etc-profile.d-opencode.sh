# OpenCode lockdown, mirrored from the image ENV so login shells
# (e.g. toolbox enter) get it too. Keep in sync with the Containerfile.
export OPENCODE_DISABLE_MODELS_FETCH=1
export OPENCODE_DISABLE_AUTOUPDATE=1
export OPENCODE_DISABLE_FREE_MODELS=1
