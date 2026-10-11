FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends openssl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY fixtures.py run.py ./
ENV LAB_BIND=0.0.0.0
CMD ["python", "-u", "run.py"]
