FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE 1
ENV PYTHONUNBUFFERED 1

RUN apt-get update && apt-get install ffmpeg libsm6 libxext6  -y

COPY ./requirements.txt ./requirements.txt
RUN pip install -r requirements.txt

COPY ./ ./

CMD ["python", "-m", "flask", "run", "--with-threads"]