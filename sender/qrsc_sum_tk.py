import tkinter as tk


def fs(s):
    total=0
    for ch in s:
        if ch not in "; \t\n\r":
            total+=ord(ch)
    return total


class CLipboardSum(tk.Tk):
    def __init__(self):
        super().__init__()
        self.overrideredirect(True)
        self.attributes('-topmost', True)
        self.geometry('120x50')
        self.configure(bg='black')
        self.last_text=''
        self.drag=None
        self.lab=tk.Label(self,text='',bg='black',fg='white',font=('TkDefaultFont',20),anchor='center')
        self.lab.pack(fill='both',expand=True)
        self.lab.bind('<Button-1>',self.down)
        self.lab.bind('<B1-Motion>',self.move)
        self.lab.bind('<ButtonRelease-1>',self.up)
        self.lab.bind('<Button-3>',lambda e:self.destroy())
        self.bind('<Button-1>',self.down)
        self.bind('<B1-Motion>',self.move)
        self.bind('<ButtonRelease-1>',self.up)
        self.bind('<Button-3>',lambda e:self.destroy())
        self.check_clipboard()

    def check_clipboard(self):
        try:
            text=self.clipboard_get()
        except tk.TclError:
            text=''
        if text!=self.last_text:
            self.last_text=text
            self.lab.config(text=str(fs(text)))
        self.after(300,self.check_clipboard)

    def down(self,e):
        self.drag=(e.x_root-self.winfo_x(),e.y_root-self.winfo_y())

    def move(self,e):
        if self.drag:
            self.geometry('+%d+%d'%(e.x_root-self.drag[0],e.y_root-self.drag[1]))

    def up(self,e):
        self.drag=None


if __name__=='__main__':
    CLipboardSum().mainloop()
