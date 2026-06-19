"""
Configuration de l'automation.
Adaptez APP_PACKAGE et les sélecteurs UI à votre application.
"""

APP_PACKAGE = "com.votre.app"  # Package Android de votre app
APP_ACTIVITY = ".MainActivity"  # Activité principale

# Sélecteurs pour le formulaire d'inscription
# Valeurs possibles: resource_id, text, description (accessibility)
REGISTER_SELECTORS = {
    "open_register_button": {"text": "S'inscrire"},   # Bouton pour ouvrir l'écran d'inscription
    "username_field":       {"resourceId": f"{APP_PACKAGE}:id/username"},
    "email_field":          {"resourceId": f"{APP_PACKAGE}:id/email"},
    "password_field":       {"resourceId": f"{APP_PACKAGE}:id/password"},
    "confirm_password":     {"resourceId": f"{APP_PACKAGE}:id/confirm_password"},  # Optionnel
    "submit_button":        {"resourceId": f"{APP_PACKAGE}:id/register_button"},
    "success_indicator":    {"text": "Bienvenue"},    # Texte/élément qui confirme le succès
}

# Sélecteurs pour la connexion
LOGIN_SELECTORS = {
    "email_field":    {"resourceId": f"{APP_PACKAGE}:id/login_email"},
    "password_field": {"resourceId": f"{APP_PACKAGE}:id/login_password"},
    "submit_button":  {"resourceId": f"{APP_PACKAGE}:id/login_button"},
    "success_indicator": {"text": "Accueil"},
}

# Paramètres d'automation
TIMEOUT = 10          # Secondes d'attente max pour un élément UI
DELAY_BETWEEN_ACTIONS = 0.5   # Délai entre chaque action (secondes)
DELAY_BETWEEN_ACCOUNTS = 2    # Délai entre la création de deux comptes (secondes)
