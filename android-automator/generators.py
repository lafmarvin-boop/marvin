import random
import string
from faker import Faker

fake = Faker("fr_FR")


def generate_account(index: int | None = None) -> dict:
    """Génère des données aléatoires pour un compte."""
    suffix = index if index is not None else random.randint(1000, 9999)
    username = f"{fake.user_name()}{suffix}".replace(".", "_")[:20]
    email = f"{username}@{fake.free_email_domain()}"
    password = _strong_password()
    return {
        "username": username,
        "email": email,
        "password": password,
        "display_name": fake.name(),
    }


def _strong_password(length: int = 12) -> str:
    chars = string.ascii_letters + string.digits + "!@#$%"
    password = [
        random.choice(string.ascii_uppercase),
        random.choice(string.ascii_lowercase),
        random.choice(string.digits),
        random.choice("!@#$%"),
    ]
    password += random.choices(chars, k=length - 4)
    random.shuffle(password)
    return "".join(password)
