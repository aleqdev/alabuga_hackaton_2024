from flask import Flask, request, send_from_directory, render_template
from PIL import Image
import aiohttp        
from threading import Thread
import string
import random
import asyncio
import io
import os
import json
from ultralytics import YOLO


model = YOLO("model.pt")


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
                  

def save_results(results, filenames, workid):
    classes = ["Плохая сварка","Трещины","Избыточное укрепление","Хорошая сварка","Пустоты","Брызги"]
    good_classes = ["Хорошая сварка"]
    bad_classes = ["Плохая сварка","Трещины","Избыточное укрепление","Пустоты","Брызги"]

    with open(f"./work/{workid}/filenames.txt", "w") as f:
        f.write("\n".join(filenames))

    os.mkdir(f"./work/{workid}/results")

    for filename, result in zip(filenames, results):
        result.save(f"./work/{workid}/results/{filename}")
        with open(f"./work/{workid}/results/{filename}.box", "w") as f:
            class_names = [classes[int(i)] for i in result.boxes.cls]
            verdict = "False"

            if any(good_class in class_names for good_class in good_classes):
                verdict = "True"

            if any(bad_class in class_names for bad_class in bad_classes):
                verdict = "False" # Перезаписывает True, преднамеренно

            f.write(json.dumps([
                result.boxes.xyxy.tolist(),
                class_names,
                verdict
            ]))

    with open(f"./work/{workid}/done", "w") as f:
        f.write("done")


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

        filenames = [f'{i}.png' for i in range(len(urls))]

        results = model([f'./work/{workid}/{filename}' for filename in filenames])

        save_results(results, filenames, workid)


    workid = randomword(16)
    thread = Thread(target=asyncio.run, args=(work(request.json["urls"], workid),))
    thread.start()

    return workid


@app.route('/process-upload', methods=["POST"])
def process_upload():
    async def work(files, workid):
        os.mkdir(f"./work/{workid}")

        filenames = []
        for filename, file in files.items():
            image = prepare_image(Image.open(file))

            if not filename.endswith(".png"):
                filename += ".png"

            filenames.append(filename)

            image.save(f'./work/{workid}/{filename}')

        results = model([f'./work/{workid}/{filename}' for filename in filenames])

        save_results(results, filenames, workid)


    files = {
        filename: io.BytesIO(file.read())
        for filename, file in request.files.items()
    }

    workid = randomword(16)
    thread = Thread(target=asyncio.run, args=(work(files, workid),))
    thread.start()

    return workid


@app.route('/process-bytes', methods=["POST"])
def process_bytes():
    async def work(files, workid):
        os.mkdir(f"./work/{workid}")

        filenames = []
        for filename, file in files.items():
            image = prepare_image(Image.open(file))

            if not filename.endswith(".png"):
                filename += ".png"

            filenames.append(filename)

            image.save(f'./work/{workid}/{filename}')

        results = model([f'./work/{workid}/{filename}' for filename in filenames])

        save_results(results, filenames, workid)


    files = {
        "image.png": io.BytesIO(request.data)
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


@app.route("/work/<workid>/done")
async def work_done(workid: str):
    if len(workid) != 16 or not workid.isalpha():
        return "denied"
    
    for _ in range(60*60):
        if os.path.exists(f"./work/{workid}/done"):
            break
        await asyncio.sleep(1)

    return "done"


@app.route("/work/<workid>/error")
async def work_error(workid: str):
    if len(workid) != 16 or not workid.isalpha():
        return "denied"
    
    for _ in range(60*60):
        if os.path.exists(f"./work/{workid}/error"):
            break
        await asyncio.sleep(1)

    return "error"


@app.route("/work/<workid>/filenames")
def work_filenames(workid: str):
    if len(workid) != 16 or not workid.isalpha():
        return "denied"
    
    with open(f"./work/{workid}/filenames.txt", "r") as f:
        return json.dumps(f.readlines())


@app.route("/work/<workid>/results/<filename>")
def work_result(workid: str, filename: str):
    if len(workid) != 16 or not workid.isalpha():
        return "denied"
    
    with open(f"./work/{workid}/filenames.txt", "r") as f:
        if filename not in f.read().split("\n"):
            return "denied" 
        
    return send_from_directory(f"./work/{workid}/results", filename, mimetype='image/png')


@app.route("/work/<workid>/results/box/<filename>")
def work_result_box(workid: str, filename: str):
    if len(workid) != 16 or not workid.isalpha():
        return "denied"
    
    with open(f"./work/{workid}/filenames.txt", "r") as f:
        if filename not in f.read().split("\n"):
            return "denied" 
        
    return send_from_directory(f"./work/{workid}/results", filename + ".box", mimetype='application/json')

