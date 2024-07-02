from flask import Flask, request, send_from_directory, render_template
from PIL import Image
import aiohttp        
from threading import Thread
import string
import random
import asyncio
import io
import os
from ultralytics import YOLO


model = YOLO("model.ipynb")


try:
    os.mkdir(f"./work/")
except FileExistsError:
    pass


app = Flask(__name__, template_folder="./static")


@app.route("/")
def index():
    return send_from_directory("static", "index.html")


@app.route("/api.txt")
def api():
    return send_from_directory("static", "api.txt")


@app.route("/alabuga.apk")
def apk():
    return send_from_directory("static", "alabuga.apk")


def randomword(length):
   letters = string.ascii_lowercase
   return ''.join(random.choice(letters) for i in range(length))


def prepare_image(image):
    w, h = image.size
    return image.crop((0, 0, min(w, h), min(w, h))).resize((640, 640))
                  

@app.route('/process-urls', methods=["POST"])
def process_urls():
    async def work(urls, workid):
        os.mkdir(f"./work/{workid}")
        async with aiohttp.ClientSession() as session:
            for i, url in enumerate(urls):
                async with session.get(url) as resp:
                    if resp.status == 200:
                        image = prepare_image(Image.open(io.BytesIO(await resp.read())))
                        image.save(f'./work/{workid}/{i}.png')

        results = model([f'./work/{workid}/{i}' for i in range(len(urls))])

        for result in results:
            result.show()

    workid = randomword(16)
    thread = Thread(target=asyncio.run, args=(work(request.json["urls"], workid),))
    thread.start()

    return workid


@app.route('/process-upload', methods=["POST"])
def process_upload():
    async def work(files, workid):
        os.mkdir(f"./work/{workid}")
        for filename, file in files.items():
            image = prepare_image(Image.open(file))

            if not filename.endswith(".png"):
                filename += ".png"

            image.save(f'./work/{workid}/{filename}')

        results = model([f'./work/{workid}/{filename}' for filename in files])

        for result in results:
            result.show()

    files = {
        filename: io.BytesIO(file.read())
        for filename, file in request.files.items()
    }

    workid = randomword(16)
    thread = Thread(target=asyncio.run, args=(work(files, workid),))
    thread.start()

    return workid


@app.route("/work/<workid>")
def work(workid: str):
    if len(workid) != 16 or not workid.isalpha():
        return "denied"
    
    return render_template("./work.html", workid=workid)


@app.route("/work/<workid>/data")
async def work_data(workid: str):
    if len(workid) != 16 or not workid.isalpha():
        return "denied"
    
    for i in range(60*60):
        if os.path.exists(f"./work/{workid}/done"):
            break
        await asyncio.sleep(1)

    return "OK"
